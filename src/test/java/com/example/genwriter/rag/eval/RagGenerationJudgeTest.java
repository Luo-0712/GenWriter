package com.example.genwriter.rag.eval;

import com.example.genwriter.config.DynamicChatModel;
import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.service.DocumentChunkingService;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.service.RAGPipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 生成端评测（真实 ChatModel + LLM 判官）。
 *
 * <p>流程：真实阈值检索取上下文 → 真实对话模型生成答案 → 再由同一个 ChatModel 充当判官，
 * 按无参照（reference-free）标准给出 Faithfulness、Answer Relevancy，并列出实际引用的上下文编号；
 * 引用编号映射回文档后与标注相关集合比对，得到「引用命中率」。
 *
 * <p>这样做的意义：检索指标只能说明「有没有捞到」，生成端指标才能说明「捞到的东西有没有被正确用上、
 * 有没有凭空编造」。二者合起来才是「降低检索噪声、提高资料引用命中率」的完整证据。
 *
 * <p>运行方式（依赖数据库、embedding 与对话模型 API）：
 * <pre>RAG_EVAL=1 mvn test -Dtest=RagGenerationJudgeTest</pre>
 * 未设置环境变量时整体跳过。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(RagEvalEnabledCondition.class)
class RagGenerationJudgeTest {

    private static final double THRESHOLD = RagBenchmarkDataset.PRODUCTION_THRESHOLD;
    private static final int TOP_K = RagBenchmarkDataset.PRODUCTION_TOP_K;
    /** 单条上下文进入 prompt 的最大字符数，控制 token 消耗。 */
    private static final int MAX_CHUNK_CHARS = 700;

    @Autowired
    private ChatClient chatClient;
    @Autowired
    private DynamicChatModel dynamicChatModel;
    @Autowired
    private DocumentChunkingService chunkingService;
    @Autowired
    private KnowledgeChunkService chunkService;
    @Autowired
    private RAGPipelineService ragPipelineService;
    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Autowired
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Autowired
    private EmbeddingService embeddingService;
    @Autowired
    private EmbeddingConfig embeddingConfig;
    @Autowired
    private LLMConfig llmConfig;
    @Autowired
    private DataSource dataSource;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RagEvalHarnessSupport harness;
    private List<RagBenchmarkDataset.CorpusDoc> corpus;
    private Path corpusDir;
    private String kbId;
    private String chatModelName;

    private final int queryLimit = Integer.getInteger("rag.eval.gen-queries", 10);

    // ------------------------------------------------------------------ 数据载体

    /** 判官输出：分数解析失败时为 null，且 parsed=false，计入解析失败数而不参与均值。 */
    record JudgeResult(Double faithfulness, Double answerRelevancy, List<Integer> citedIndices,
                       boolean parsed, String reason) {
    }

    private record GenRow(RagBenchmarkDataset.QueryLabel label, List<String> retrievedDocIds, String answer,
                          JudgeResult judge, boolean citationHit, boolean rejected) {
    }

    // ------------------------------------------------------------------ 生命周期

    @BeforeAll
    void setUp() {
        // 开关与 API Key 已由 RagEvalEnabledCondition 在容器级别校验；此处只做运行期前置条件检查
        harness = new RagEvalHarnessSupport(chunkingService, chunkService, ragPipelineService,
                knowledgeBaseMapper, knowledgeChunkMapper, embeddingService, embeddingConfig, dataSource);
        try {
            harness.assertPreconditions();
            chatModelName = resolveChatModel();
            Assumptions.assumeTrue(chatModelName != null,
                    "所有候选对话模型均不可用（常见原因：API 余额不足），跳过生成端评测");
            corpus = RagBenchmarkDataset.loadCorpus();
            corpusDir = Files.createTempDirectory("rag-eval-gen-corpus-");
            harness.materializeCorpus(corpus, corpusDir);
            kbId = harness.createKnowledgeBase("rag-eval-generation-" + System.currentTimeMillis());
            int chunks = harness.ingestCorpus(kbId, corpus, RagBenchmarkDataset.productionConfig());
            log("生成端评测语料入库完成: " + chunks + " chunks, 对话模型=" + chatModelName);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "评测前置条件不满足: " + e.getMessage());
        }
    }

    /**
     * 选择一个真实可用的对话模型。
     *
     * <p>生产默认模型由配置决定，但评测环境可能缺少对应供应商的余额。这里依次探测
     * {@code -Drag.eval.chat-model} 指定的模型、自动配置的 DashScope 兜底模型、以及当前激活模型，
     * 用一次极小请求验证可用性；全部不可用时返回 null，由调用方以 assumption 跳过而不是让构建失败。
     */
    private String resolveChatModel() {
        List<String> candidates = new ArrayList<>();
        String preferred = System.getProperty("rag.eval.chat-model");
        if (preferred != null && !preferred.isBlank()) {
            candidates.add(preferred);
        }
        candidates.add("dashscope");
        candidates.add(dynamicChatModel.getActiveModel());

        for (String key : candidates) {
            try {
                dynamicChatModel.switchModel(key);
            } catch (Exception e) {
                log("切换模型失败 " + key + ": " + e.getMessage());
                continue;
            }
            try {
                String reply = chatClient.prompt().user("回复两个字：可用").call().content();
                if (reply != null && !reply.isBlank()) {
                    log("对话模型可用: " + key);
                    return key;
                }
            } catch (Exception e) {
                log("对话模型不可用 " + key + ": " + rootMessage(e));
            }
        }
        return null;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    @AfterAll
    void tearDown() throws IOException {
        if (harness != null && kbId != null) {
            harness.dropKnowledgeBase(kbId);
        }
        if (corpusDir != null) {
            deleteRecursively(corpusDir);
        }
    }

    // ------------------------------------------------------------------ 主评测

    @Test
    void evaluate() throws Exception {
        List<RagBenchmarkDataset.QueryLabel> queries = RagBenchmarkDataset.answerableQueries();
        List<GenRow> rows = new ArrayList<>();

        for (int i = 0; i < Math.min(queryLimit, queries.size()); i++) {
            RagBenchmarkDataset.QueryLabel label = queries.get(i);
            List<KnowledgeChunkDTO> contexts = harness.retrieveRaw(kbId, label.query(), TOP_K, THRESHOLD);
            if (contexts.isEmpty()) {
                rows.add(new GenRow(label, List.of(), "", null, false, true));
                log("query=" + shorten(label.query()) + " → 阈值过滤后无语境，判定为拒答");
                continue;
            }
            List<String> docIds = contexts.stream().map(c -> harness.docIdOf(c.getSourceId())).toList();
            try {
                String answer = generateAnswer(label.query(), contexts);
                JudgeResult judge = judge(label.query(), contexts, answer);
                boolean citationHit = judge.parsed() && judge.citedIndices().stream()
                        .filter(idx -> idx >= 1 && idx <= contexts.size())
                        .map(idx -> harness.docIdOf(contexts.get(idx - 1).getSourceId()))
                        .anyMatch(label.relevantDocs()::contains);
                rows.add(new GenRow(label, docIds, answer, judge, citationHit, false));
                log("生成 query=" + shorten(label.query())
                        + " → Faithfulness=" + (judge.faithfulness() == null ? "n/a"
                        : RagEvalReportWriter.num4(judge.faithfulness()))
                        + ", Relevancy=" + (judge.answerRelevancy() == null ? "n/a"
                        : RagEvalReportWriter.num4(judge.answerRelevancy()))
                        + ", 引用命中=" + citationHit);
            } catch (Exception e) {
                // 单条失败不应让整轮评测作废，记录为「未解析」并继续
                rows.add(new GenRow(label, docIds, "", new JudgeResult(null, null, List.of(), false,
                        "调用失败: " + rootMessage(e)), false, false));
                log("生成 query=" + shorten(label.query()) + " 调用失败: " + rootMessage(e));
            }
        }

        RagEvalReportWriter writer = new RagEvalReportWriter("RAG 生成端评测报告（真实 ChatModel + LLM 判官）")
                .meta("配置的默认模型", llmConfig.getDefaultProvider() + " / " + llmConfig.getDefaultModel())
                .meta("实际使用的对话模型", chatModelName + "（生成与判官共用）")
                .meta("检索配置", "阈值 " + THRESHOLD + " / topK " + TOP_K + "（rerank 关闭）")
                .meta("评测 query 数", rows.size() + " / " + queries.size())
                .meta("判官标准", "无参照（reference-free）：Faithfulness 与 Answer Relevancy，1.0 为最好")
                .processSection(
                        "生成端与检索端共用同一份语料与同一份「一节一文件」标注，因此检索结果可严格映射回文档。",
                        "先走生产阈值路径取回 top-K chunk 作为 grounding 上下文，按 `[序号] (doc: 文档ID)` 编号后拼进 prompt。",
                        "判官被要求只输出 JSON：faithfulness / answer_relevancy / cited_context_indices，"
                                + "解析失败只记录不参与均值；引用编号映射回文档后与标注相关集合求交得到引用命中率。");

        writer.section("逐条结果", detailText(rows));
        writer.section("聚合指标", aggregateText(rows));
        Path out = writer.write("RAG生成端评测报告");
        log("报告已写入: " + out.toAbsolutePath());
        System.out.println("\n" + writer.render());
    }

    // ------------------------------------------------------------------ 生成与判官

    private String generateAnswer(String question, List<KnowledgeChunkDTO> contexts) {
        String system = "你是写作技法知识助手。只依据给定的参考资料回答问题；"
                + "参考资料不足以回答时，明确说明「资料不足以回答」，不要编造。回答用简体中文，控制在 200 字以内。";
        String user = "参考资料：\n" + renderContexts(contexts)
                + "\n\n问题：" + question
                + "\n\n请给出回答，并在句末用 [序号] 标注你所依据的资料编号。";
        return chatClient.prompt().system(system).user(user).call().content();
    }

    private JudgeResult judge(String question, List<KnowledgeChunkDTO> contexts, String answer) {
        String system = "你是严格的事实核查与问答质量评审员。你只输出一个 JSON 对象，不要输出任何解释性文字或代码块标记。";
        String user = "请评审下面的 RAG 回答。\n\n"
                + "参考资料：\n" + renderContexts(contexts) + "\n\n"
                + "问题：" + question + "\n\n"
                + "回答：" + answer + "\n\n"
                + "评审要求：\n"
                + "1. faithfulness：回答中的每条事实性陈述是否都能在参考资料中找到依据；"
                + "存在无依据或与资料矛盾的内容则扣分。取值 0.0~1.0。\n"
                + "2. answer_relevancy：回答是否直接且完整地回应了问题（即使资料不足，明确说明也算相关）。取值 0.0~1.0。\n"
                + "3. cited_context_indices：回答实际依据的资料编号数组，例如 [1,3]；没有依据任何资料则为 []。\n\n"
                + "只输出如下 JSON：\n"
                + "{\"faithfulness\": 0.0, \"answer_relevancy\": 0.0, \"cited_context_indices\": [], \"reason\": \"一句话理由\"}";
        String raw = chatClient.prompt().system(system).user(user).call().content();
        return parseJudge(raw, contexts.size());
    }

    /** 容错解析判官输出。包内可见以便被 {@link RagJudgeParsingTest} 独立验证，不需要调用外部模型。 */
    JudgeResult parseJudge(String raw, int contextCount) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new JudgeResult(null, null, List.of(), false, "未找到 JSON: " + shorten(raw));
            }
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            Double faithfulness = clamp(node.get("faithfulness"));
            Double relevancy = clamp(node.get("answer_relevancy"));
            List<Integer> cited = new ArrayList<>();
            JsonNode citedNode = node.get("cited_context_indices");
            if (citedNode != null && citedNode.isArray()) {
                for (JsonNode item : citedNode) {
                    if (item.isInt()) {
                        cited.add(item.asInt());
                    }
                }
            }
            cited.removeIf(idx -> idx < 1 || idx > contextCount);
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : "";
            return new JudgeResult(faithfulness, relevancy, cited, faithfulness != null || relevancy != null, reason);
        } catch (Exception e) {
            return new JudgeResult(null, null, List.of(), false, "解析异常: " + e.getMessage());
        }
    }

    private static Double clamp(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.asDouble();
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 渲染带编号的 grounding 上下文。包内可见以便被 {@link RagJudgeParsingTest} 独立验证。 */
    static String renderContexts(List<KnowledgeChunkDTO> contexts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contexts.size(); i++) {
            KnowledgeChunkDTO ctx = contexts.get(i);
            String content = ctx.getContent() == null ? "" : ctx.getContent().replaceAll("\\s+", " ").strip();
            if (content.length() > MAX_CHUNK_CHARS) {
                content = content.substring(0, MAX_CHUNK_CHARS) + "…";
            }
            sb.append('[').append(i + 1).append("] (doc: ").append(ctx.getSourceId()).append(")\n")
                    .append(content).append("\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 报告文本

    private String detailText(List<GenRow> rows) {
        List<List<String>> tableRows = new ArrayList<>();
        for (GenRow row : rows) {
            JudgeResult judge = row.judge();
            tableRows.add(List.of(
                    shorten(row.label().query()),
                    row.rejected() ? "0（拒答）" : String.valueOf(row.retrievedDocIds().size()),
                    judge == null || judge.faithfulness() == null ? "n/a" : RagEvalReportWriter.num4(judge.faithfulness()),
                    judge == null || judge.answerRelevancy() == null ? "n/a"
                            : RagEvalReportWriter.num4(judge.answerRelevancy()),
                    judge == null ? "n/a" : String.valueOf(judge.citedIndices()),
                    judge != null && judge.parsed() ? (row.citationHit() ? "命中" : "未命中") : "未解析",
                    judge != null && judge.parsed() ? shorten(judge.reason()) : "-"));
        }
        return RagEvalReportWriter.table(
                List.of("query", "取回 chunk 数", "Faithfulness", "AnswerRelevancy", "引用编号", "引用命中", "判官理由"),
                tableRows);
    }

    private String aggregateText(List<GenRow> rows) {
        List<Double> faithfulness = new ArrayList<>();
        List<Double> relevancy = new ArrayList<>();
        long parsed = 0;
        long citationHit = 0;
        long rejected = 0;
        for (GenRow row : rows) {
            if (row.rejected()) {
                rejected++;
                continue;
            }
            JudgeResult judge = row.judge();
            if (judge != null && judge.parsed()) {
                parsed++;
                if (judge.faithfulness() != null) {
                    faithfulness.add(judge.faithfulness());
                }
                if (judge.answerRelevancy() != null) {
                    relevancy.add(judge.answerRelevancy());
                }
                if (row.citationHit()) {
                    citationHit++;
                }
            }
        }

        List<List<String>> tableRows = new ArrayList<>();
        tableRows.add(List.of("Faithfulness（均值）",
                faithfulness.isEmpty() ? "n/a" : RagEvalReportWriter.num4(RetrievalMetrics.average(faithfulness))));
        tableRows.add(List.of("Answer Relevancy（均值）",
                relevancy.isEmpty() ? "n/a" : RagEvalReportWriter.num4(RetrievalMetrics.average(relevancy))));
        tableRows.add(List.of("引用命中率",
                parsed == 0 ? "n/a" : RagEvalReportWriter.pct((double) citationHit / parsed)));
        tableRows.add(List.of("判官解析成功数", parsed + " / " + rows.size()));
        tableRows.add(List.of("阈值导致的拒答数", String.valueOf(rejected)));

        StringBuilder sb = new StringBuilder();
        sb.append("判官为无参照评审，存在合理波动；建议与检索端指标（Recall@K / 引用命中）结合解读。\n\n");
        sb.append(RagEvalReportWriter.table(List.of("指标", "数值"), tableRows));
        sb.append("\n> 结论方向：Faithfulness 高说明检索到的资料被正确使用、未出现明显幻觉；")
                .append("引用命中率高说明「被引用的资料」确实落在标注相关集合内，即噪声被有效压制。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 通用工具

    private static String shorten(String text) {
        if (text == null) {
            return "-";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 36 ? flat : flat.substring(0, 36) + "…";
    }

    private static void log(String message) {
        System.out.println("[RAG-EVAL] " + message);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
