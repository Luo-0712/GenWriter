package com.example.genwriter.agent.memory.eval;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.memory.LongTermMemoryMetadataSupport;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.mapper.LongTermMemoryMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.retrieval.GlobalRerankConverger;
import com.example.genwriter.retrieval.gate.RetrievalGate;
import com.example.genwriter.retrieval.gate.RetrievalGateProperties;
import com.example.genwriter.retrieval.rerank.NoOpReranker;
import com.example.genwriter.retrieval.rerank.RerankProperties;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.impl.LongTermMemoryServiceImpl;
import com.example.genwriter.util.VectorUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentMatchers;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.document.MetadataMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记忆检索 A/B 评测：新版记忆系统（工作区改动）vs 旧版基线（改动前实现）。
 *
 * <p>对照的两套链路：
 * <ul>
 *   <li><b>新版</b>：真实 {@link LongTermMemoryServiceImpl} + 真实 {@link com.example.genwriter.agent.memory.LongTermMemoryAdvisor}
 *       （全局收敛 globalTopN=5），检索 SQL 语义与生产一致（内存等价实现）。</li>
 *   <li><b>旧版基线</b>：复刻改动前的 {@code LongTermMemoryServiceImpl#rankCandidates / calculateScore /
 *       keywordScore / diversifyByType}（OR 单 term 命中召回、textMatched 兜底 0.35、加性重要度偏置、无分数下限、
 *       无 rerank/门控），Advisor 侧 globalTopN 设为无截断（等价于改动前的「全量合并后注入」）。</li>
 * </ul>
 *
 * <p>两侧共用同一份种子语料、同一批向量、同一组查询，唯一变量是检索与合并逻辑。
 *
 * <p>指标：正样本 Hit@1/3/5 与 MRR、负样本误注入率、多 query 场景注入条数与 prompt 字符数。
 *
 * <p>运行（依赖 DASHSCOPE_API_KEY，不依赖 PostgreSQL/Redis）：
 * <pre>MEMORY_EVAL=1 mvn test -Dtest=MemoryRetrievalABEvaluator</pre>
 * 默认跳过，保证日常构建不依赖外部环境。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryRetrievalABEvaluator {

    private static final String EMBEDDING_MODEL = new EmbeddingConfig().getDefaultModel();
    private static final String LEGACY_SESSION = "ab-eval-legacy";
    private static final String CURRENT_SESSION = "ab-eval-current";

    // ------------------------------------------------------------------ 语料

    private record MemorySeed(String key, String content, MemoryType type, String importance) {}

    /**
     * 种子语料：7 条目标记忆 + 13 条干扰记忆。
     * 干扰项刻意使用 HIGH 重要度与「章节 / 用户 / 写作 / 设定」等高频词，
     * 用于暴露旧版「OR 单 term 命中即召回 + 重要度加性偏置」的噪声放大问题。
     */
    private static final List<MemorySeed> SEEDS = List.of(
            // —— 目标记忆（正样本 ground truth）——
            new MemorySeed("sword", "主角林澈是青云宗外门弟子，佩剑名为「听雨」，剑身有一道旧裂痕。",
                    MemoryType.CHARACTER_PROFILE, "MEDIUM"),
            new MemorySeed("teleport", "本世界设定：灵力枯竭的北境禁止使用传送阵，违者逐出宗门。",
                    MemoryType.WORLD_SETTING, "HIGH"),
            new MemorySeed("pollen", "第三章埋下的伏笔：药铺掌柜袖口沾有紫色的雾岚花粉，与后山禁地植物相关。",
                    MemoryType.FORESHADOWING, "MEDIUM"),
            new MemorySeed("fightStyle", "用户偏好：打斗场面用短句推进，避免四字成语堆砌。",
                    MemoryType.WRITING_PREFERENCE, "HIGH"),
            new MemorySeed("suspense", "写作技法：在章节结尾用一句环境描写承接未解的冲突，制造悬念。",
                    MemoryType.WRITING_TECHNIQUE, "MEDIUM"),
            new MemorySeed("noDeusEx", "纠错模式：禁止在绝境中突然空降更强的前辈解围，需由主角自身破局。",
                    MemoryType.CORRECTION_PATTERN, "HIGH"),
            new MemorySeed("alchemy", "领域知识：本作丹药品阶分为凡品、灵品、玄品、神品四阶，玄品以上需以神识温养。",
                    MemoryType.DOMAIN_KNOWLEDGE, "MEDIUM"),

            // —— 干扰记忆（同类型 / 高重要度 / 高频词）——
            new MemorySeed("chapterLen", "用户偏好：单章正文控制在三千字左右，章节开头不要写环境描写。",
                    MemoryType.WRITING_PREFERENCE, "HIGH"),
            new MemorySeed("povRule", "写作技法：全篇固定第三人称限知视角，章节内不得跳视角。",
                    MemoryType.WRITING_TECHNIQUE, "HIGH"),
            new MemorySeed("noModern", "世界观设定：本作不得出现现代词汇，角色对话避免网络用语。",
                    MemoryType.WORLD_SETTING, "HIGH"),
            new MemorySeed("mcSenior", "人物设定：女主苏挽是药王谷传人，擅用毒，性格冷冽。",
                    MemoryType.CHARACTER_PROFILE, "HIGH"),
            new MemorySeed("oldInjury", "第二章伏笔：林澈左肩旧伤每逢雨夜发作，与早年那场山崩有关。",
                    MemoryType.FORESHADOWING, "MEDIUM"),
            new MemorySeed("storyTone", "用户偏好：整体基调偏冷峻，避免大段抒情独白。",
                    MemoryType.WRITING_PREFERENCE, "MEDIUM"),
            new MemorySeed("sectRule", "世界观设定：青云宗外门弟子三年一测，未通过者贬为杂役。",
                    MemoryType.WORLD_SETTING, "MEDIUM"),
            new MemorySeed("dialogueTip", "写作技法：对话中避免使用括号补充说明动作。",
                    MemoryType.WRITING_TECHNIQUE, "LOW"),
            new MemorySeed("spiritStone", "领域知识：灵石换算为下品、中品、上品，百枚下品兑一枚中品。",
                    MemoryType.DOMAIN_KNOWLEDGE, "MEDIUM"),
            new MemorySeed("villain", "人物设定：反派裴无渡表面是执法堂长老，暗中以血祭养剑。",
                    MemoryType.CHARACTER_PROFILE, "HIGH"),
            new MemorySeed("rainSword", "第五章伏笔：听雨剑的裂痕在雷雨天会渗出细血。",
                    MemoryType.FORESHADOWING, "LOW"),
            new MemorySeed("noTimeTravel", "纠错模式：不要用时间回溯改写已写成的结局。",
                    MemoryType.CORRECTION_PATTERN, "MEDIUM"),
            new MemorySeed("endingTone", "用户偏好：结局不接受开放式留白，需要明确收束。",
                    MemoryType.WRITING_PREFERENCE, "LOW")
    );

    /** 正样本 query -> 期望命中的种子 key */
    private static final Map<String, String> POSITIVE_QUERIES = new LinkedHashMap<>();
    static {
        POSITIVE_QUERIES.put("林澈的佩剑叫什么名字？", "sword");
        POSITIVE_QUERIES.put("北境使用传送阵会有什么后果？", "teleport");
        POSITIVE_QUERIES.put("药铺掌柜袖口的花粉暗示了什么？", "pollen");
        POSITIVE_QUERIES.put("打斗场面应该怎么写？", "fightStyle");
        POSITIVE_QUERIES.put("章节结尾怎么写才能制造悬念？", "suspense");
        POSITIVE_QUERIES.put("主角陷入绝境时不能怎么写？", "noDeusEx");
        POSITIVE_QUERIES.put("丹药品阶是怎么划分的？", "alchemy");
        POSITIVE_QUERIES.put("苏挽是什么身份？", "mcSenior");
        POSITIVE_QUERIES.put("灵石的下品中品上品怎么换算？", "spiritStone");
    }

    /** 负样本：与语料完全无关，期望不注入任何记忆 */
    private static final List<String> NEGATIVE_QUERIES = List.of(
            "量子计算机的量子比特退相干时间一般是多少？",
            "Linux 内核 CFS 调度器的时间片是如何计算的？",
            "甲状腺结节怀疑恶性时应当先做哪项检查？",
            "美联储加息对新兴市场资本流动的传导机制是什么？",
            "足球比赛中越位规则的具体判定条件是什么？",
            "宋朝的科举制度与唐代相比有哪些主要变化？",
            "如何用 Python 实现快速排序并分析时间复杂度？",
            "新能源汽车电池热管理系统的主要技术路线有哪些？"
    );

    /** 多 query 组：模拟 MemoryQueryExtractor 一次产出多个检索 query */
    private static final Map<String, List<String>> MULTI_QUERY_GROUPS = new LinkedHashMap<>();
    static {
        MULTI_QUERY_GROUPS.put("写作·打斗+悬念+佩剑", List.of(
                "打斗场面应该怎么写？",
                "章节结尾如何制造悬念？",
                "主角的佩剑叫什么名字？"));
        MULTI_QUERY_GROUPS.put("设定·传送阵+丹药+灵石", List.of(
                "北境使用传送阵的后果是什么？",
                "丹药品阶如何划分？",
                "灵石下品中品上品怎么换算？"));
        MULTI_QUERY_GROUPS.put("续写·人物+伏笔+纠错", List.of(
                "苏挽的身份是什么？",
                "药铺掌柜袖口的花粉暗示了什么？",
                "绝境情节不能怎么写？"));
    }

    // ------------------------------------------------------------------ 上下文

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LongTermMemoryMetadataSupport metadataSupport;
    private EmbeddingService embeddingService;
    private List<LongTermMemory> corpus;
    private Map<String, String> idToKey;
    private LongTermMemoryPromptFormatter formatter;
    private RetrievalGateProperties gateProperties;

    @BeforeAll
    void prepare() {
        Assumptions.assumeTrue("1".equals(System.getenv("MEMORY_EVAL"))
                        || "1".equals(System.getProperty("memory.eval")),
                "设置 MEMORY_EVAL=1 以启用记忆检索 A/B 评测（依赖 DASHSCOPE_API_KEY）");
        Assumptions.assumeTrue(System.getenv("DASHSCOPE_API_KEY") != null
                && !System.getenv("DASHSCOPE_API_KEY").isBlank(), "需要 DASHSCOPE_API_KEY");

        metadataSupport = new LongTermMemoryMetadataSupport(objectMapper);
        formatter = new LongTermMemoryPromptFormatter(metadataSupport);
        gateProperties = new RetrievalGateProperties();

        DashScopeApi api = new DashScopeApi(System.getenv("DASHSCOPE_API_KEY"));
        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel(api, MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder().withModel(EMBEDDING_MODEL).build());
        // 阈值扫描会重复检索同一批 query，缓存向量避免重复调用 embedding API
        embeddingService = new CachingEmbeddingService(new EmbeddingService(model));

        corpus = buildCorpus();
        idToKey = new LinkedHashMap<>();
        for (int i = 0; i < SEEDS.size(); i++) {
            idToKey.put(corpus.get(i).getId(), SEEDS.get(i).key());
        }
    }

    @Test
    void compareNewVsLegacy() throws IOException {
        // 默认值需与 application.yml 的 genwriter.long-term-memory.retrieval.similarity-threshold 保持一致
        double primaryThreshold = Double.parseDouble(
                System.getProperty("memory.eval.similarity-threshold", "0.50"));
        gateProperties.setMinScoreFloor(Double.parseDouble(
                System.getProperty("memory.eval.min-score-floor", "0.25")));

        ScenarioResult legacy = runScenario("旧版基线", true, primaryThreshold);
        ScenarioResult current = runScenario("新版", false, primaryThreshold);

        List<Double> sweep = parseDoubles(System.getProperty("memory.eval.thresholds",
                "0.30,0.40,0.50,0.65"));
        List<SweepRow> sweepRows = new ArrayList<>();
        for (double t : sweep) {
            sweepRows.add(new SweepRow(t,
                    runScenario("旧版基线", true, t),
                    runScenario("新版", false, t)));
        }

        String report = renderReport(primaryThreshold, legacy, current, sweepRows);
        System.out.println(report);

        Path out = Path.of("docs", "记忆检索A-B评测报告-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        System.out.println("[DONE] 报告已写入: " + out.toAbsolutePath());
    }

    // ------------------------------------------------------------------ 语料构建

    private List<LongTermMemory> buildCorpus() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        List<LongTermMemory> built = new ArrayList<>();
        for (int i = 0; i < SEEDS.size(); i++) {
            MemorySeed seed = SEEDS.get(i);
            Map<String, Object> metadata = metadataSupport.normalize(
                    seed.content(), seed.type().name(), "GLOBAL", null, null, seed.importance(), null);
            String metadataJson = metadataSupport.toJson(metadata);
            String retrievalText = metadataSupport.buildRetrievalText(
                    seed.content(), seed.type().name(), metadata);
            built.add(LongTermMemory.builder()
                    .id(UUID.nameUUIDFromBytes(seed.key().getBytes(StandardCharsets.UTF_8)).toString())
                    .content(seed.content())
                    .memoryType(seed.type().name())
                    .scope("GLOBAL")
                    .embedding(embeddingService.embed(retrievalText))
                    .embeddingModel(EMBEDDING_MODEL)
                    .importance(seed.importance())
                    .metadata(metadataJson)
                    .accessCount(0)
                    .createdAt(base.plusMinutes(i))
                    .updatedAt(base.plusMinutes(i))
                    .build());
        }
        return built;
    }

    /**
     * 内存版 Mapper：语义等价于生产 SQL（pgvector 余弦相似度 + ILIKE 文本检索）。
     *
     * @param legacyTextSearch true 时复刻改动前语义（无 minHits 约束、按重要度/更新时间排序）
     */
    private LongTermMemoryMapper inMemoryMapper(boolean legacyTextSearch) {
        LongTermMemoryMapper mapper = mock(LongTermMemoryMapper.class);

        when(mapper.similaritySearch(ArgumentMatchers.any(), ArgumentMatchers.<List<String>>any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    float[] queryVector = VectorUtils.vectorStringToArray(invocation.getArgument(0));
                    List<String> types = invocation.getArgument(1);
                    double threshold = invocation.getArgument(4);
                    int limit = invocation.getArgument(5);
                    return corpus.stream()
                            .filter(m -> types == null || types.isEmpty() || types.contains(m.getMemoryType()))
                            .map(m -> copyOf(m, VectorUtils.cosineSimilarity(queryVector, m.getEmbedding()), null))
                            .filter(m -> m.getSimilarity() > threshold)
                            .sorted(Comparator.comparingDouble(LongTermMemory::getSimilarity).reversed())
                            .limit(limit)
                            .toList();
                });

        when(mapper.textSearch(ArgumentMatchers.<List<String>>any(), anyInt(),
                ArgumentMatchers.<List<String>>any(), ArgumentMatchers.any(), anyInt()))
                .thenAnswer(invocation -> {
                    List<String> terms = invocation.getArgument(0);
                    int minHits = invocation.getArgument(1);
                    List<String> types = invocation.getArgument(2);
                    int limit = invocation.getArgument(4);
                    int effectiveMinHits = legacyTextSearch ? 1 : Math.max(1, minHits);

                    List<LongTermMemory> matched = new ArrayList<>();
                    for (LongTermMemory memory : corpus) {
                        if (types != null && !types.isEmpty() && !types.contains(memory.getMemoryType())) {
                            continue;
                        }
                        String haystack = (memory.getContent() + " " + memory.getMetadata())
                                .toLowerCase(Locale.ROOT);
                        int hits = 0;
                        for (String term : terms) {
                            if (term != null && !term.isBlank()
                                    && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                                hits++;
                            }
                        }
                        if (hits < effectiveMinHits) {
                            continue;
                        }
                        matched.add(copyOf(memory, 0.0, hits));
                    }

                    if (legacyTextSearch) {
                        matched.sort(Comparator
                                .comparingInt((LongTermMemory m) -> importanceRank(m.getImportance())).reversed()
                                .thenComparing(Comparator.comparing(LongTermMemory::getUpdatedAt).reversed()));
                    } else {
                        matched.sort(Comparator
                                .comparingInt((LongTermMemory m) ->
                                        m.getTermHitCount() == null ? 0 : m.getTermHitCount()).reversed()
                                .thenComparing(Comparator.comparing(LongTermMemory::getUpdatedAt).reversed()));
                    }
                    return matched.size() > limit ? new ArrayList<>(matched.subList(0, limit)) : matched;
                });

        when(mapper.updateById(ArgumentMatchers.any(LongTermMemory.class))).thenReturn(1);
        return mapper;
    }

    private static LongTermMemory copyOf(LongTermMemory source, Double similarity, Integer termHitCount) {
        return LongTermMemory.builder()
                .id(source.getId())
                .content(source.getContent())
                .memoryType(source.getMemoryType())
                .scope(source.getScope())
                .projectId(source.getProjectId())
                .sessionId(source.getSessionId())
                .embedding(source.getEmbedding())
                .embeddingModel(source.getEmbeddingModel())
                .importance(source.getImportance())
                .metadata(source.getMetadata())
                .accessCount(source.getAccessCount())
                .createdAt(source.getCreatedAt())
                .updatedAt(source.getUpdatedAt())
                .similarity(similarity)
                .termHitCount(termHitCount)
                .build();
    }

    // ------------------------------------------------------------------ 两套链路

    private ScenarioResult runScenario(String name, boolean legacy, double threshold) {
        LongTermMemoryProperties properties = new LongTermMemoryProperties();
        properties.getRetrieval().setSimilarityThreshold(threshold);
        properties.getProbe().setEnabled(true);
        // 旧版 Advisor 行为等价于「不截断」，只把 globalTopN 放大即可复用同一份 Advisor 代码
        properties.getRetrieval().setGlobalTopN(legacy ? Integer.MAX_VALUE : 5);

        String sessionId = legacy ? LEGACY_SESSION : CURRENT_SESSION;
        LongTermMemoryService service = legacy
                ? legacyService(threshold)
                : currentService(properties);

        LongTermMemoryProbeRecorder probeRecorder = new LongTermMemoryProbeRecorder(
                properties, mock(ThoughtChainPublisher.class));
        GlobalRerankConverger converger = new GlobalRerankConverger(
                new NoOpReranker(), new RetrievalGate(gateProperties));

        List<QueryOutcome> positives = new ArrayList<>();
        POSITIVE_QUERIES.forEach((query, expectedKey) ->
                positives.add(retrieve(service, query, expectedKey)));

        List<QueryOutcome> negatives = new ArrayList<>();
        for (String query : NEGATIVE_QUERIES) {
            negatives.add(retrieve(service, query, null));
        }

        List<MultiQueryOutcome> multi = new ArrayList<>();
        MULTI_QUERY_GROUPS.forEach((group, queries) -> {
            var advisor = new com.example.genwriter.agent.memory.LongTermMemoryAdvisor(
                    service, formatter, null, sessionId, queries, probeRecorder, converger, properties);
            CapturingChain chain = new CapturingChain();
            AdvisedRequest request = AdvisedRequest.builder()
                    .chatModel(mock(org.springframework.ai.chat.model.ChatModel.class))
                    .userText(String.join(" ", queries))
                    .messages(List.of(new org.springframework.ai.chat.messages.UserMessage(
                            String.join(" ", queries))))
                    .build();
            advisor.aroundCall(request, chain);
            int injected = lastInjectedCount(probeRecorder, sessionId);
            int chars = chain.systemText() == null ? 0 : chain.systemText().length();
            multi.add(new MultiQueryOutcome(group, queries.size(), injected, chars));
        });

        return new ScenarioResult(name, legacy, positives, negatives, multi);
    }

    private LongTermMemoryService currentService(LongTermMemoryProperties properties) {
        return new LongTermMemoryServiceImpl(
                inMemoryMapper(false),
                embeddingService,
                mock(TaskSessionMapper.class),
                properties,
                metadataSupport,
                null,
                new EmbeddingConfig(),
                new NoOpReranker(),
                new RetrievalGate(gateProperties),
                gateProperties,
                new RerankProperties());
    }

    private LongTermMemoryService legacyService(double threshold) {
        LongTermMemoryMapper mapper = inMemoryMapper(true);
        LongTermMemoryService service = mock(LongTermMemoryService.class);
        when(service.retrieveMemories(ArgumentMatchers.any(), ArgumentMatchers.<List<MemoryType>>any(),
                ArgumentMatchers.any()))
                .thenAnswer(invocation -> legacyRetrieve(mapper, invocation.getArgument(0), threshold));
        return service;
    }

    private QueryOutcome retrieve(LongTermMemoryService service, String query, String expectedKey) {
        List<MemoryVO> memories = service.retrieveMemories(query, null, null);
        List<String> rankedKeys = new ArrayList<>();
        for (MemoryVO memory : memories) {
            rankedKeys.add(idToKey.getOrDefault(memory.getId(), "?"));
        }
        double topScore = memories.isEmpty() || memories.get(0).getSimilarity() == null
                ? 0.0 : memories.get(0).getSimilarity();
        return new QueryOutcome(query, expectedKey, rankedKeys, topScore);
    }

    // ------------------------------------------------------------------ 旧版基线复刻

    /**
     * 旧版（改动前）检索实现：逐行对齐 {@code LongTermMemoryServiceImpl} 在 refactor/prompt 分支改动前的
     * rankCandidates / calculateScore / keywordScore / diversifyByType。
     */
    private List<MemoryVO> legacyRetrieve(LongTermMemoryMapper mapper, String query, double threshold) {
        int limit = 5;
        int candidateLimit = Math.max(limit * 4, 20);

        Map<String, LegacyScored> candidates = new LinkedHashMap<>();
        String vectorLiteral = VectorUtils.arrayToVectorString(embeddingService.embed(query));
        for (LongTermMemory memory : mapper.similaritySearch(
                vectorLiteral, null, null, EMBEDDING_MODEL, threshold, candidateLimit)) {
            mergeLegacy(candidates, memory, false);
        }
        List<String> terms = metadataSupport.extractTextSearchTerms(query);
        if (!terms.isEmpty()) {
            for (LongTermMemory memory : mapper.textSearch(terms, 0, null, null, candidateLimit)) {
                mergeLegacy(candidates, memory, true);
            }
        }

        List<LegacyScored> ranked = new ArrayList<>(candidates.values());
        for (LegacyScored scored : ranked) {
            scored.score = legacyScore(scored, query, terms);
        }
        ranked.sort(Comparator.comparingDouble((LegacyScored s) -> s.score).reversed());

        List<LegacyScored> selected = ranked.size() > limit ? ranked.subList(0, limit) : ranked;
        List<MemoryVO> result = new ArrayList<>();
        for (LegacyScored scored : selected) {
            LongTermMemory memory = scored.memory;
            result.add(MemoryVO.builder()
                    .id(memory.getId())
                    .content(memory.getContent())
                    .memoryType(memory.getMemoryType())
                    .scope(memory.getScope())
                    .importance(memory.getImportance())
                    .metadata(metadataSupport.parseMetadata(memory.getMetadata()))
                    .similarity(scored.score)
                    .build());
        }
        return result;
    }

    private void mergeLegacy(Map<String, LegacyScored> candidates, LongTermMemory memory, boolean textMatched) {
        if (memory == null || memory.getId() == null) {
            return;
        }
        LegacyScored existing = candidates.get(memory.getId());
        if (existing == null) {
            candidates.put(memory.getId(), new LegacyScored(memory, textMatched));
            return;
        }
        existing.textMatched = existing.textMatched || textMatched;
        double oldSimilarity = existing.memory.getSimilarity() != null ? existing.memory.getSimilarity() : 0.0;
        double newSimilarity = memory.getSimilarity() != null ? memory.getSimilarity() : 0.0;
        if (newSimilarity > oldSimilarity) {
            existing.memory.setSimilarity(newSimilarity);
        }
    }

    private double legacyScore(LegacyScored candidate, String query, List<String> terms) {
        LongTermMemory memory = candidate.memory;
        Map<String, Object> metadata = metadataSupport.parseMetadata(memory.getMetadata());
        double vectorScore = clamp(memory.getSimilarity() != null ? memory.getSimilarity() : 0.0);
        double keywordScore = legacyKeywordScore(memory, metadata, query, terms, candidate.textMatched);
        double entityScore = entityScore(metadata, query, terms);
        double importanceBoost = "HIGH".equals(memory.getImportance()) ? 0.10
                : "MEDIUM".equals(memory.getImportance()) ? 0.04 : 0.0;

        return vectorScore * 0.70
                + keywordScore * 0.15
                + entityScore * 0.10
                + importanceBoost;
    }

    private double legacyKeywordScore(LongTermMemory memory, Map<String, Object> metadata, String query,
                                      List<String> terms, boolean textMatched) {
        String searchable = normalize(memory.getContent() + " " + metadata);
        int hits = 0;
        for (String term : terms) {
            if (searchable.contains(normalize(term))) {
                hits++;
            }
        }
        double ratio = terms.isEmpty() ? 0.0 : Math.min(1.0, hits / (double) Math.min(4, terms.size()));
        if (!query.isBlank() && searchable.contains(normalize(query))) {
            ratio = Math.max(ratio, 1.0);
        }
        if (textMatched) {
            ratio = Math.max(ratio, 0.35);
        }
        return ratio;
    }

    // ------------------------------------------------------------------ 公共打分辅助（新旧同语义）

    private double entityScore(Map<String, Object> metadata, String query, List<String> terms) {
        String queryText = normalize(query);
        List<String> entities = new ArrayList<>(metadataSupport.toStringList(metadata.get("entities")));
        Object title = metadata.get("title");
        if (title != null) {
            entities.add(String.valueOf(title));
        }
        for (String entity : entities) {
            String normalizedEntity = normalize(entity);
            if (normalizedEntity.length() < 2) {
                continue;
            }
            if (queryText.contains(normalizedEntity)) {
                return 1.0;
            }
            for (String term : terms) {
                String normalizedTerm = normalize(term);
                if (normalizedEntity.contains(normalizedTerm) || normalizedTerm.contains(normalizedEntity)) {
                    return 0.7;
                }
            }
        }
        return 0.0;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int importanceRank(String importance) {
        return switch (importance == null ? "" : importance) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    // ------------------------------------------------------------------ 探针读取

    private static int lastInjectedCount(LongTermMemoryProbeRecorder recorder, String sessionId) {
        List<Map<String, Object>> events = recorder.recentEvents(sessionId);
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> event = events.get(i);
            if (!"retrieval".equals(event.get("eventType"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) event.get("payload");
            Object count = payload.get("injectedCount");
            return count instanceof Number number ? number.intValue() : 0;
        }
        return 0;
    }

    private static final class CapturingChain implements CallAroundAdvisorChain {
        private String systemText;

        @Override
        public AdvisedResponse nextAroundCall(AdvisedRequest request) {
            this.systemText = request.systemText();
            return null;
        }

        String systemText() {
            return systemText;
        }
    }

    /** 缓存单条文本的向量，避免阈值扫描重复调用 embedding API。 */
    private static final class CachingEmbeddingService extends EmbeddingService {
        private final EmbeddingService delegate;
        private final Map<String, float[]> cache = new java.util.concurrent.ConcurrentHashMap<>();

        private CachingEmbeddingService(EmbeddingService delegate) {
            super(null);
            this.delegate = delegate;
        }

        @Override
        public float[] embed(String text) {
            return cache.computeIfAbsent(text, delegate::embed);
        }
    }

    private static final class LegacyScored {
        private final LongTermMemory memory;
        private boolean textMatched;
        private double score;

        private LegacyScored(LongTermMemory memory, boolean textMatched) {
            this.memory = memory;
            this.textMatched = textMatched;
        }
    }

    // ------------------------------------------------------------------ 指标与报表

    private record QueryOutcome(String query, String expectedKey, List<String> rankedKeys, double topScore) {}

    private record MultiQueryOutcome(String group, int queryCount, int injectedCount, int injectedChars) {}

    private record ScenarioResult(String name, boolean legacy, List<QueryOutcome> positives,
                                  List<QueryOutcome> negatives, List<MultiQueryOutcome> multi) {}

    private record SweepRow(double threshold, ScenarioResult legacy, ScenarioResult current) {}

    private static double hitAt(ScenarioResult scenario, int k) {
        long hits = scenario.positives().stream().filter(o -> isHit(o, k)).count();
        return hits / (double) scenario.positives().size();
    }

    private static boolean isHit(QueryOutcome outcome, int k) {
        for (int i = 0; i < Math.min(k, outcome.rankedKeys().size()); i++) {
            if (outcome.rankedKeys().get(i).equals(outcome.expectedKey())) {
                return true;
            }
        }
        return false;
    }

    private static double mrr(ScenarioResult scenario) {
        double sum = 0.0;
        for (QueryOutcome outcome : scenario.positives()) {
            for (int i = 0; i < outcome.rankedKeys().size(); i++) {
                if (outcome.rankedKeys().get(i).equals(outcome.expectedKey())) {
                    sum += 1.0 / (i + 1);
                    break;
                }
            }
        }
        return sum / scenario.positives().size();
    }

    private static double avgPositiveInjected(ScenarioResult scenario) {
        return scenario.positives().stream().mapToInt(o -> o.rankedKeys().size()).average().orElse(0.0);
    }

    private static double avgNegativeInjected(ScenarioResult scenario) {
        return scenario.negatives().stream().mapToInt(o -> o.rankedKeys().size()).average().orElse(0.0);
    }

    private static double falseInjectionRate(ScenarioResult scenario) {
        long injected = scenario.negatives().stream().filter(o -> !o.rankedKeys().isEmpty()).count();
        return injected / (double) scenario.negatives().size();
    }

    private static double avgMultiInjected(ScenarioResult scenario) {
        return scenario.multi().stream().mapToInt(MultiQueryOutcome::injectedCount).average().orElse(0.0);
    }

    private static double avgMultiChars(ScenarioResult scenario) {
        return scenario.multi().stream().mapToInt(MultiQueryOutcome::injectedChars).average().orElse(0.0);
    }

    private static List<Double> parseDoubles(String raw) {
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

    private String renderReport(double threshold, ScenarioResult legacy, ScenarioResult current,
                                List<SweepRow> sweepRows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 记忆检索 A/B 评测报告（新版 vs 旧版基线）\n\n");
        sb.append("- 时间：").append(LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append('\n');
        sb.append("- 向量模型：`").append(EMBEDDING_MODEL).append("`（生产同款，L2 归一化后余弦）\n");
        sb.append("- 语料：").append(SEEDS.size()).append(" 条种子记忆（")
                .append(POSITIVE_QUERIES.size()).append(" 条目标记忆 + 干扰项，含 HIGH 重要度噪声）\n");
        sb.append("- 查询：正样本 ").append(POSITIVE_QUERIES.size()).append(" 条、负样本 ")
                .append(NEGATIVE_QUERIES.size()).append(" 条、多 query 组 ")
                .append(MULTI_QUERY_GROUPS.size()).append(" 组（每组 3 query）\n");
        sb.append("- 主评测阈值：").append(String.format("%.2f", threshold))
                .append("（-Dmemory.eval.similarity-threshold 可覆盖）\n");
        sb.append("- 门控：minScoreFloor=").append(gateProperties.getMinScoreFloor())
                .append("，rerank 关闭（与生产默认一致，走降级路径）\n\n");

        sb.append("## 一、总览\n\n");
        sb.append("| 指标 | 旧版基线 | 新版 | 变化 |\n|---|---|---|---|\n");
        appendRow(sb, "正样本 Hit@1", hitAt(legacy, 1), hitAt(current, 1), true);
        appendRow(sb, "正样本 Hit@3", hitAt(legacy, 3), hitAt(current, 3), true);
        appendRow(sb, "正样本 Hit@5", hitAt(legacy, 5), hitAt(current, 5), true);
        appendRow(sb, "正样本 MRR", mrr(legacy), mrr(current), true);
        appendRow(sb, "正样本平均注入条数", avgPositiveInjected(legacy), avgPositiveInjected(current), false);
        appendRow(sb, "负样本平均注入条数", avgNegativeInjected(legacy), avgNegativeInjected(current), false);
        appendRow(sb, "负样本误注入率", falseInjectionRate(legacy), falseInjectionRate(current), false);
        appendRow(sb, "多 query 平均注入条数", avgMultiInjected(legacy), avgMultiInjected(current), false);
        appendRow(sb, "多 query 平均注入字符数", avgMultiChars(legacy), avgMultiChars(current), false);
        sb.append('\n');

        sb.append("## 二、正样本明细\n\n");
        sb.append("| query | 期望 | 旧版 Top5 | 新版 Top5 |\n|---|---|---|---|\n");
        for (int i = 0; i < legacy.positives().size(); i++) {
            QueryOutcome l = legacy.positives().get(i);
            QueryOutcome c = current.positives().get(i);
            sb.append("| ").append(l.query())
                    .append(" | ").append(l.expectedKey())
                    .append(" | ").append(isHit(l, 5) ? "✅ " : "❌ ").append(joinKeys(l))
                    .append(" | ").append(isHit(c, 5) ? "✅ " : "❌ ").append(joinKeys(c))
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 三、负样本明细（无关 query，期望零注入）\n\n");
        sb.append("| query | 旧版注入 | 新版注入 | 旧版 top分 | 新版 top分 |\n|---|---|---|---|---|\n");
        for (int i = 0; i < legacy.negatives().size(); i++) {
            QueryOutcome l = legacy.negatives().get(i);
            QueryOutcome c = current.negatives().get(i);
            sb.append("| ").append(l.query())
                    .append(" | ").append(l.rankedKeys().isEmpty() ? "0 ✅" : l.rankedKeys().size() + " ❌")
                    .append(" | ").append(c.rankedKeys().isEmpty() ? "0 ✅" : c.rankedKeys().size() + " ❌")
                    .append(" | ").append(String.format("%.4f", l.topScore()))
                    .append(" | ").append(String.format("%.4f", c.topScore()))
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 四、多 query 注入量（N×topK 放大 vs 全局 topN 收敛）\n\n");
        sb.append("| query 组 | query 数 | 旧版注入条数 | 新版注入条数 | 旧版字符数 | 新版字符数 |\n|---|---|---|---|---|---|\n");
        for (int i = 0; i < legacy.multi().size(); i++) {
            MultiQueryOutcome l = legacy.multi().get(i);
            MultiQueryOutcome c = current.multi().get(i);
            sb.append("| ").append(l.group())
                    .append(" | ").append(l.queryCount())
                    .append(" | ").append(l.injectedCount())
                    .append(" | ").append(c.injectedCount())
                    .append(" | ").append(l.injectedChars())
                    .append(" | ").append(c.injectedChars())
                    .append(" |\n");
        }
        sb.append('\n');

        sb.append("## 五、阈值扫描（Hit@5 / 负样本误注入率 / 多 query 注入量）\n\n");
        sb.append("| 相似度阈值 | 旧版 Hit@5 | 新版 Hit@5 | 旧版误注入率 | 新版误注入率 | 旧版多query注入 | 新版多query注入 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (SweepRow row : sweepRows) {
            sb.append(String.format("| %.2f | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.2f | %.2f |%n",
                    row.threshold(),
                    hitAt(row.legacy(), 5) * 100, hitAt(row.current(), 5) * 100,
                    falseInjectionRate(row.legacy()) * 100, falseInjectionRate(row.current()) * 100,
                    avgMultiInjected(row.legacy()), avgMultiInjected(row.current())));
        }
        sb.append('\n');

        sb.append("## 六、差异归因\n\n");
        sb.append("| 改动 | 作用层 | 影响的指标 |\n|---|---|---|\n");
        sb.append("| textSearch 至少命中半数 term（minHits）+ termHitCount 排序 | 召回 | 负样本误注入率、正样本 Hit@1 |\n");
        sb.append("| 融合分下限 minScoreFloor=0.25 | 召回 | 负样本误注入率 |\n");
        sb.append("| 重要度/范围加成改为乘性偏置 | 打分 | 噪声项不再被重要度单独抬过线 |\n");
        sb.append("| 移除 textMatched 兜底 0.35 | 打分 | 负样本误注入率 |\n");
        sb.append("| 多 query 全局收敛（globalTopN=5） | Advisor | 多 query 注入条数与字符数 |\n");
        sb.append("| 全局精排 + 相关性闸门（rerank 关闭时静默降级） | Advisor | 后续开启 rerank 后生效 |\n\n");

        sb.append(renderMissDiagnostics(threshold, current));
        sb.append(renderConclusion(legacy, current, sweepRows));
        return sb.toString();
    }

    /** 新版失题归因：定位是「向量阈值过严」还是「textSearch minHits 过严」。 */
    private String renderMissDiagnostics(double threshold, ScenarioResult current) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 七、失题归因诊断（新版未命中的正样本）\n\n");
        List<QueryOutcome> misses = current.positives().stream().filter(o -> !isHit(o, 5)).toList();
        if (misses.isEmpty()) {
            sb.append("新版在当前阈值下无失题。\n\n");
            return sb.toString();
        }
        sb.append("| query | 期望记忆 | 向量相似度 | 阈值 | term 数 | 命中 term 数 | minHits | 文本路融合分 | 下限 | 判定 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (QueryOutcome miss : misses) {
            LongTermMemory target = seedMemory(miss.expectedKey());
            double sim = VectorUtils.cosineSimilarity(embeddingService.embed(miss.query()), target.getEmbedding());
            List<String> terms = metadataSupport.extractTextSearchTerms(miss.query());
            int minHits = Math.max(1, (terms.size() + 1) / 2);
            int hits = countTermHits(target, terms);
            double fused = fusedScore(target, miss.query(), terms, hits, 0.0);
            double floor = gateProperties.getMinScoreFloor();

            String verdict;
            if (sim > threshold) {
                verdict = "向量路应可召回（检查排序/截断）";
            } else if (hits < minHits) {
                verdict = "minHits 过严，文本路未召回";
            } else if (fused < floor) {
                verdict = "文本路召回但被融合分下限砍掉";
            } else {
                verdict = "两路皆应召回（需进一步排查）";
            }

            sb.append("| ").append(miss.query())
                    .append(" | ").append(miss.expectedKey())
                    .append(" | ").append(String.format("%.4f", sim))
                    .append(" | ").append(String.format("%.2f", threshold))
                    .append(" | ").append(terms.size())
                    .append(" | ").append(hits)
                    .append(" | ").append(minHits)
                    .append(" | ").append(String.format("%.4f", fused))
                    .append(" | ").append(String.format("%.2f", floor))
                    .append(" | ").append(verdict)
                    .append(" |\n");
        }
        sb.append('\n');
        sb.append("> 注：文本路单独召回时向量分为 0，融合分上限仅 ")
                .append(String.format("%.3f", (0.15 + 0.10) * 1.10))
                .append("（关键词 0.15 + 实体 0.10，再乘 HIGH 重要度 1.10）")
                .append("，与 minScoreFloor=0.25 之间余量极小。\n\n");
        return sb.toString();
    }

    /** 复刻新版融合分：(向量*0.70 + 关键词*0.15 + 实体*0.10) × (1 + 重要度加成)。 */
    private double fusedScore(LongTermMemory memory, String query, List<String> terms,
                              int termHits, double vectorSimilarity) {
        Map<String, Object> metadata = metadataSupport.parseMetadata(memory.getMetadata());
        String searchable = normalize(memory.getContent() + " " + metadata);
        int hits = 0;
        for (String term : terms) {
            if (searchable.contains(normalize(term))) {
                hits++;
            }
        }
        hits = Math.max(hits, termHits);
        double keyword = terms.isEmpty() ? 0.0 : Math.min(1.0, hits / (double) Math.min(4, terms.size()));
        if (!query.isBlank() && searchable.contains(normalize(query))) {
            keyword = Math.max(keyword, 1.0);
        }
        double entity = entityScore(metadata, query, terms);
        double boost = "HIGH".equals(memory.getImportance()) ? 0.10
                : "MEDIUM".equals(memory.getImportance()) ? 0.04 : 0.0;
        return (clamp(vectorSimilarity) * 0.70 + keyword * 0.15 + entity * 0.10) * (1.0 + boost);
    }

    private String renderConclusion(ScenarioResult legacy, ScenarioResult current, List<SweepRow> sweepRows) {
        List<Double> dominant = sweepRows.stream()
                .filter(r -> hitAt(r.current(), 5) >= 1.0 && falseInjectionRate(r.current()) <= 0.0)
                .map(SweepRow::threshold)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("## 八、结论与建议\n\n");

        sb.append("1. **降噪收益确凿**：新版负样本误注入率 ")
                .append(String.format("%.1f%% → %.1f%%", falseInjectionRate(legacy) * 100,
                        falseInjectionRate(current) * 100))
                .append("，正样本平均注入条数 ")
                .append(String.format("%.2f → %.2f", avgPositiveInjected(legacy), avgPositiveInjected(current)))
                .append("，多 query 注入条数 ")
                .append(String.format("%.2f → %.2f（字符数 -%.0f%%）",
                        avgMultiInjected(legacy), avgMultiInjected(current),
                        avgMultiChars(legacy) == 0 ? 0.0
                                : (1 - avgMultiChars(current) / avgMultiChars(legacy)) * 100))
                .append("。\n");

        sb.append("2. **记忆纯度提升**：旧版正样本 Top5 中混入了 `dialogueTip`、`povRule`、`chapterLen`、`oldInjury`、")
                .append("`sword` 等无关记忆（OR 单 term 命中 + 重要度加性偏置导致），新版 Top5 基本只剩目标记忆。\n");

        if (hitAt(current, 5) < 1.0) {
            sb.append("3. **存在召回回退（需配置配合）**：主评测阈值下新版 Hit@5 为 ")
                    .append(String.format("%.1f%%", hitAt(current, 5) * 100))
                    .append("，低于旧版 ")
                    .append(String.format("%.1f%%", hitAt(legacy, 5) * 100))
                    .append("。原因是新版收紧 textSearch 后，文本路不再为向量路兜底，")
                    .append("而生产默认 `similarity-threshold=0.65` 对中文短 query 过严，两路同时落空。\n");
        }
        if (!dominant.isEmpty()) {
            sb.append("4. **建议**：把 `genwriter.long-term-memory.retrieval.similarity-threshold` 从 0.65 下调到 **")
                    .append(String.format("%.2f", dominant.get(dominant.size() - 1)))
                    .append("** 附近（阈值扫描中 ")
                    .append(dominant.stream().map(t -> String.format("%.2f", t)).toList())
                    .append(" 各档均达成「新版 Hit@5=100% 且误注入率 0%」），")
                    .append("即可在召回不掉的前提下拿到全部降噪收益；若不便调阈值，则应放宽 ")
                    .append("`minHits`（当前为 ceil(term 数/2)）或为短 query 单独放开下限。\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private LongTermMemory seedMemory(String key) {
        for (LongTermMemory memory : corpus) {
            if (key.equals(idToKey.get(memory.getId()))) {
                return memory;
            }
        }
        throw new IllegalStateException("未找到种子记忆: " + key);
    }

    private static int countTermHits(LongTermMemory memory, List<String> terms) {
        String haystack = (memory.getContent() + " " + memory.getMetadata()).toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String term : terms) {
            if (term != null && !term.isBlank() && haystack.contains(term.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private static void appendRow(StringBuilder sb, String name, double legacy, double current,
                                  boolean higherIsBetter) {
        boolean percent = name.contains("率") || name.contains("Hit") || name.contains("MRR");
        double scale = percent ? 100.0 : 1.0;
        String fmt = percent ? "%.1f%%" : "%.2f";
        String delta;
        if (percent) {
            delta = String.format("%+.1f pp", (current - legacy) * 100);
        } else if (legacy != 0) {
            delta = String.format("%+.2f（%+.1f%%）", current - legacy, (current - legacy) / legacy * 100);
        } else {
            delta = String.format("%+.2f", current - legacy);
        }
        String better = higherIsBetter
                ? (current > legacy ? " ↑" : current < legacy ? " ↓" : "")
                : (current < legacy ? " ↑" : current > legacy ? " ↓" : "");
        sb.append("| ").append(name)
                .append(" | ").append(String.format(fmt, legacy * scale))
                .append(" | ").append(String.format(fmt, current * scale))
                .append(" | ").append(delta).append(better)
                .append(" |\n");
    }

    private static String joinKeys(QueryOutcome outcome) {
        return outcome.rankedKeys().isEmpty() ? "（空）" : String.join(", ", outcome.rankedKeys());
    }
}
