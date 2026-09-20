package com.example.genwriter.agent.memory.eval;

import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆检索评测（基线 + 门控验证）。
 *
 * <p>指标：
 * <ul>
 *   <li>正样本：Hit@1 / Hit@5（查询应命中的种子记忆）</li>
 *   <li>负样本（UNRELATED_QUERIES）：无关误注入率（top1 分数越过判定阈值即视为误注入）与拒答率</li>
 *   <li>thresholdStats：多阈值下的注入判定对照（阈值经 -Dmemory.eval.thresholds 配置，不再硬编码）</li>
 *   <li>门控拒绝率：从探针事件统计 gateDecision 的 pass/reject 分布</li>
 * </ul>
 *
 * <p>运行方式（依赖本地 PostgreSQL/Redis 与 DASHSCOPE_API_KEY）：
 * <pre>MEMORY_EVAL=1 mvn test -Dtest=MemoryRetrievalEvaluator</pre>
 * 默认跳过，保证日常构建不依赖外部环境。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryRetrievalEvaluator {

    /** 种子记忆：id 前缀 + 内容 + 类型 */
    private record MemorySeed(String key, String content, MemoryType type) {}

    private static final List<MemorySeed> SEEDS = List.of(
            new MemorySeed("protagonist", "主角林澈是青云宗外门弟子，佩剑名为「听雨」，剑身有一道旧裂痕。",
                    MemoryType.CHARACTER_PROFILE),
            new MemorySeed("worldRule", "本世界设定：灵力枯竭的北境禁止使用传送阵，违者逐出宗门。",
                    MemoryType.WORLD_SETTING),
            new MemorySeed("foreshadow", "第三章埋下的伏笔：药铺掌柜袖口沾有紫色的雾岚花粉，与后山禁地植物相关。",
                    MemoryType.FORESHADOWING),
            new MemorySeed("style", "用户偏好：打斗场面用短句推进，避免四字成语堆砌。",
                    MemoryType.WRITING_PREFERENCE),
            new MemorySeed("technique", "写作技法：在章节结尾用一句环境描写承接未解的冲突，制造悬念。",
                    MemoryType.WRITING_TECHNIQUE)
    );

    private static final List<String> UNRELATED_QUERIES = List.of(
            "量子计算机的量子比特退相干时间一般是多少？",
            "甲状腺结节怀疑恶性时应当先做哪项检查？",
            "Linux 内核进程调度器 CFS 的时间片是如何计算的？",
            "美联储加息对新兴市场资本流动的传导机制是什么？",
            "足球比赛中越位规则的具体判定条件是什么？",
            "宋朝的科举制度与唐代相比有哪些主要变化？"
    );

    /** 正样本 query -> 期望命中的种子 key */
    private static final Map<String, String> POSITIVE_QUERIES = Map.of(
            "林澈的佩剑叫什么名字？", "protagonist",
            "北境使用传送阵会有什么后果？", "worldRule",
            "药铺掌柜袖口的花粉暗示了什么？", "foreshadow",
            "用户喜欢什么样的打斗场面写法？", "style",
            "章节结尾怎么写才能制造悬念？", "technique"
    );

    @Autowired
    private LongTermMemoryService memoryService;

    @Autowired
    private LongTermMemoryProbeRecorder probeRecorder;

    private String sessionId;

    @BeforeAll
    void assumeEnabled() {
        Assumptions.assumeTrue("1".equals(System.getenv("MEMORY_EVAL"))
                        || "1".equals(System.getProperty("memory.eval")),
                "设置 MEMORY_EVAL=1 以启用记忆检索评测（依赖数据库与 embedding API）");
    }

    @Test
    void evaluate() {
        sessionId = "eval-" + System.currentTimeMillis();
        List<String> createdIds = new ArrayList<>();

        try {
            for (MemorySeed seed : SEEDS) {
                createdIds.add(memoryService.storeMemory(
                        seed.content(), seed.type(), "GLOBAL", null, sessionId, "MEDIUM", null).getId());
            }

            List<PositiveResult> positives = evalPositives();
            List<UnrelatedResult> unrelated = evalUnrelated();

            double hit1 = positives.stream().filter(PositiveResult::hitAt1).count() / (double) positives.size();
            double hit5 = positives.stream().filter(PositiveResult::hitAt5).count() / (double) positives.size();
            long injections = unrelated.stream().filter(r -> r.topSimilarity >= evalThreshold()).count();
            double falseInjectionRate = unrelated.isEmpty() ? 0.0 : injections / (double) unrelated.size();

            renderReport(positives, hit1, hit5, unrelated, falseInjectionRate, gateStats());
        } finally {
            if (!createdIds.isEmpty()) {
                try {
                    memoryService.deleteMemories(createdIds);
                } catch (Exception ignored) {
                    // 评测数据清理失败不影响结论输出
                }
            }
        }
    }

    private List<PositiveResult> evalPositives() {
        List<PositiveResult> results = new ArrayList<>();
        POSITIVE_QUERIES.forEach((query, expectedKey) -> {
            List<com.example.genwriter.model.dto.response.MemoryVO> memories =
                    memoryService.retrieveMemories(query, null, sessionId);
            boolean hitAt1 = memories.size() > 0 && containsSeed(memories.get(0), expectedKey);
            boolean hitAt5 = memories.stream().limit(5).anyMatch(m -> containsSeed(m, expectedKey));
            double topSimilarity = memories.isEmpty() || memories.get(0).getSimilarity() == null
                    ? 0.0 : memories.get(0).getSimilarity();
            results.add(new PositiveResult(query, expectedKey, hitAt1, hitAt5, topSimilarity));
        });
        return results;
    }

    private List<UnrelatedResult> evalUnrelated() {
        List<UnrelatedResult> results = new ArrayList<>();
        for (String query : UNRELATED_QUERIES) {
            List<com.example.genwriter.model.dto.response.MemoryVO> memories =
                    memoryService.retrieveMemories(query, null, sessionId);
            double topSimilarity = memories.isEmpty() || memories.get(0).getSimilarity() == null
                    ? 0.0 : memories.get(0).getSimilarity();
            results.add(new UnrelatedResult(query, topSimilarity, memories.size()));
        }
        return results;
    }

    private boolean containsSeed(com.example.genwriter.model.dto.response.MemoryVO memory, String seedKey) {
        return memory.getContent() != null && SEEDS.stream()
                .filter(seed -> seed.key().equals(seedKey))
                .findFirst()
                .map(seed -> memory.getContent().contains(seed.content()))
                .orElse(false);
    }

    /** 注入判定阈值：默认 0.65（基线值），可用 -Dmemory.eval.similarity-threshold 覆盖 */
    private static double evalThreshold() {
        return Double.parseDouble(System.getProperty("memory.eval.similarity-threshold", "0.65"));
    }

    /** 多阈值对照（thresholdStats）：阈值列表经 -Dmemory.eval.thresholds 配置，不再硬编码 0.65 */
    private static List<Double> thresholds() {
        String raw = System.getProperty("memory.eval.thresholds", "0.55,0.60,0.65,0.70,0.75");
        List<Double> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                result.add(Double.parseDouble(part.trim()));
            } catch (NumberFormatException ignored) {
                // 跳过非法阈值项
            }
        }
        return result;
    }

    private Map<String, Integer> gateStats() {
        Map<String, Integer> stats = new LinkedHashMap<>();
        try {
            for (Map<String, Object> event : probeRecorder.recentEvents(null)) {
                if (!"gateDecision".equals(event.get("eventType"))) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                boolean pass = Boolean.TRUE.equals(payload.get("pass"));
                stats.merge(pass ? "pass" : "reject", 1, Integer::sum);
            }
        } catch (Exception ignored) {
            // 探针未开启时忽略
        }
        return stats;
    }

    private void renderReport(List<PositiveResult> positives, double hit1, double hit5,
                              List<UnrelatedResult> unrelated, double falseInjectionRate,
                              Map<String, Integer> gateStats) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== 长期记忆检索评测报告 =====\n");
        sb.append(String.format("正样本 Hit@1=%.1f%% Hit@5=%.1f%%%n", hit1 * 100, hit5 * 100));
        for (PositiveResult p : positives) {
            sb.append(String.format("  [%s] %s -> 期望=%s topSim=%.4f%n",
                    p.hitAt1() ? "HIT@1 " : p.hitAt5() ? "HIT@5 " : "MISS  ", p.query(), p.expectedKey(),
                    p.topSimilarity()));
        }
        sb.append(String.format("负样本无关误注入率=%.1f%% (threshold=%.2f) 拒答率=%.1f%%%n",
                falseInjectionRate * 100, evalThreshold(), (1 - falseInjectionRate) * 100));
        for (UnrelatedResult u : unrelated) {
            sb.append(String.format("  [%s] %s -> topSim=%.4f injected=%d%n",
                    u.topSimilarity() >= evalThreshold() ? "INJECTED" : "REJECT  ", u.query(),
                    u.topSimilarity(), u.injectedCount()));
        }

        sb.append("\nthresholdStats（多阈值注入判定对照）：\n");
        sb.append("| threshold | 正样本 top1 越阈值数 | 负样本 top1 越阈值数 |\n");
        List<UnrelatedResult> unrelatedFinal = unrelated;
        for (double t : thresholds()) {
            long pos = positives.stream().filter(p -> p.topSimilarity() >= t).count();
            long neg = unrelatedFinal.stream().filter(u -> u.topSimilarity() >= t).count();
            sb.append(String.format("| %.2f | %d/%d | %d/%d |%n", t, pos, positives.size(), neg, unrelatedFinal.size()));
        }

        sb.append("\n门控决策统计（探针事件）：").append(gateStats.isEmpty() ? "未开启探针" : gateStats).append('\n');
        System.out.println(sb);
    }

    private record PositiveResult(String query, String expectedKey, boolean hitAt1, boolean hitAt5,
                                  double topSimilarity) {}

    private record UnrelatedResult(String query, double topSimilarity, int injectedCount) {}
}
