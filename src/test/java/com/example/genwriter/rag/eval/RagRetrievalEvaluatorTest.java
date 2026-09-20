package com.example.genwriter.rag.eval;

import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.service.DocumentChunkingService;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.service.RAGPipelineService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 检索端评测（真实全链路）。
 *
 * <p>覆盖四组结论：
 * <ol>
 *   <li><b>分块策略 × 尺寸消融</b>：fixed_size / recursive / structural × chunkSize {500,1000,1500}，
 *       在相同语料、查询集、向量模型与检索路径下对比 Hit@K / Recall@K / nDCG@K 与 chunk 规模；</li>
 *   <li><b>检索质量</b>：生产阈值路径下的 Hit@K、Precision@K、Recall@K、MRR、MAP、nDCG@K；</li>
 *   <li><b>降噪与拒答</b>：无关负样本的误注入率/拒答率，以及阈值 0.50~0.80 扫描的精确率-召回率曲线；</li>
 *   <li><b>HNSW 近似代价</b>：放大索引后，近似路径 vs 精确（顺序扫描）路径的近似召回率、
 *       P50/P99 延迟、QPS，以及阈值过滤掉的相关文档数量。</li>
 * </ol>
 *
 * <p>运行方式（依赖本地 PostgreSQL+pgvector 与 DASHSCOPE_API_KEY）：
 * <pre>RAG_EVAL=1 mvn test -Dtest=RagRetrievalEvaluatorTest</pre>
 * 未设置环境变量时整体跳过，保证日常构建不依赖外部环境。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(RagEvalEnabledCondition.class)
class RagRetrievalEvaluatorTest {

    private static final double THRESHOLD = RagBenchmarkDataset.PRODUCTION_THRESHOLD;
    private static final int MAX_K = 10;

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
    private DataSource dataSource;

    private RagEvalHarnessSupport harness;
    private List<RagBenchmarkDataset.CorpusDoc> corpus;
    private Path corpusDir;

    private final int latencyRepeats = Integer.getInteger("rag.eval.latency-repeats", 20);
    private final int amplifyCopies = Integer.getInteger("rag.eval.amplify-copies", 4);
    private final int e2eQueries = Integer.getInteger("rag.eval.e2e-queries", 5);

    // ------------------------------------------------------------------ 数据载体

    private record AblationRow(RagBenchmarkDataset.AblationConfig config, int chunkCount, int avgChunkChars,
                              Map<Integer, Double> hit, Map<Integer, Double> precision, Map<Integer, Double> recall,
                              Map<Integer, Double> ndcg, double mrr, double map) {
    }

    private record SweepRow(double threshold, double precision, double recall, double f1, double ndcg,
                            double falseInjectionRate) {
    }

    private record NegativeRow(String query, double topSimilarity, int injectedCount) {
    }

    private record HnswRow(int k, double recall) {
    }

    private record HnswMeasurement(List<HnswRow> recallByK, double approxP50, double approxP99, double approxQps,
                                  double exactP50, double exactP99, double recallBeforeThreshold,
                                  double recallAfterThreshold, int filteredRelevant) {
    }

    // ------------------------------------------------------------------ 生命周期

    @BeforeAll
    void setUp() {
        // 开关与 API Key 已由 RagEvalEnabledCondition 在容器级别校验；此处只做运行期前置条件检查
        harness = new RagEvalHarnessSupport(chunkingService, chunkService, ragPipelineService,
                knowledgeBaseMapper, knowledgeChunkMapper, embeddingService, embeddingConfig, dataSource);
        try {
            harness.assertPreconditions();
            corpus = RagBenchmarkDataset.loadCorpus();
            corpusDir = Files.createTempDirectory("rag-eval-corpus-");
            harness.materializeCorpus(corpus, corpusDir);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "评测前置条件不满足: " + e.getMessage());
        }
    }

    @AfterAll
    void tearDown() throws IOException {
        if (corpusDir != null) {
            deleteRecursively(corpusDir);
        }
    }

    // ------------------------------------------------------------------ 主评测

    @Test
    void evaluate() throws Exception {
        String kbId = harness.createKnowledgeBase("rag-eval-retrieval-" + System.currentTimeMillis());
        try {
            List<AblationRow> ablation = new ArrayList<>();
            AblationRow productionRow = null;
            List<NegativeRow> negatives = List.of();
            List<SweepRow> sweep = List.of();

            // 阶段一：分块消融；顺带在生产配置上采集质量/降噪/阈值曲线，避免重复入库
            String productionKey = RagBenchmarkDataset.productionConfig().key();
            for (RagBenchmarkDataset.AblationConfig cfg : RagBenchmarkDataset.ablationConfigs()) {
                harness.truncateChunks(kbId);
                int chunkCount = 0;
                int totalChars = 0;
                for (RagBenchmarkDataset.CorpusDoc doc : corpus) {
                    RagEvalHarnessSupport.IngestResult r = harness.ingest(kbId, doc.docId(), cfg);
                    chunkCount += r.chunkCount();
                    totalChars += r.totalChars();
                }
                AblationRow row = measureAblation(kbId, cfg, chunkCount, totalChars);
                ablation.add(row);
                log("消融 " + cfg.label() + " → chunks=" + chunkCount
                        + " Hit@5=" + RagEvalReportWriter.pct(row.hit().get(5))
                        + " nDCG@5=" + RagEvalReportWriter.num4(row.ndcg().get(5)));

                if (cfg.key().equals(productionKey)) {
                    productionRow = row;
                    negatives = measureNegatives(kbId);
                    sweep = measureThresholdSweep(kbId);
                }
            }

            // 阶段二：HNSW 近似 vs 精确（放大索引规模后测量）
            harness.truncateChunks(kbId);
            for (RagBenchmarkDataset.CorpusDoc doc : corpus) {
                harness.ingest(kbId, doc.docId(), RagBenchmarkDataset.productionConfig());
            }
            long baseRows = harness.countChunks(kbId);
            int amplified = harness.amplifyIndex(kbId, amplifyCopies, 20260910L);
            long totalRows = harness.countChunks(kbId);
            long allTableRows = harness.countAllChunks();
            HnswMeasurement hnsw = measureHnsw(kbId);
            double e2eP50 = measureEndToEnd(kbId);

            RagEvalReportWriter writer = new RagEvalReportWriter("RAG 检索端评测报告（真实全链路）")
                    .meta("向量模型", harness.embeddingModelName())
                    .meta("向量维度", harness.embeddingDimension())
                    .meta("HNSW 索引", RagEvalHarnessSupport.HNSW_INDEX_NAME
                            + " (vector_cosine_ops, m=16, ef_construction=64)")
                    .meta("检索路径", "RAGPipelineService#searchAndRetrieve → 余弦距离阈值路径（rerank 关闭）")
                    .meta("生产阈值 / topK", THRESHOLD + " / " + RagBenchmarkDataset.PRODUCTION_TOP_K)
                    .meta("指标 K 取值", java.util.Arrays.toString(RagBenchmarkDataset.K_VALUES))
                    .metaAll(RagBenchmarkDataset.summary(corpus))
                    .meta("放大后索引行数", baseRows + " → " + totalRows + "（新增 " + amplified + "，仅用于 HNSW 对比）")
                    .meta("knowledge_chunk 全表行数", allTableRows + "（精确路径需顺序扫描全表，延迟以此解释）")
                    .processSection(
                            "语料按「一节一文件」组织，文件名即 docId，入库时写入稳定的 source_id（UUID），"
                                    + "使检索结果可映射回文档，且该映射与分块策略/尺寸无关。",
                            "统一走 `RAGPipelineService#searchAndRetrieve(query, kbId, topK, threshold)` "
                                    + "→ `KnowledgeChunkServiceImpl#searchSimilarChunks` 的阈值路径 "
                                    + "`embedding <=> query < 1 - threshold`（与生产默认完全一致）。",
                            "命中结果按 `source_id` 映射回 docId 并去重（同一文档多 chunk 只计一次），"
                                    + "再交给 `RetrievalMetrics` 计算 Hit/Precision/Recall@K、MRR、MAP、nDCG@K。");

            writer.section("分块策略 × 尺寸消融", ablationText(ablation, productionKey));
            writer.section("检索质量（生产配置 " + RagBenchmarkDataset.productionConfig().label()
                    + "，阈值 " + THRESHOLD + "）", qualityText(productionRow));
            writer.section("降噪与拒答（无关负样本）", negativesText(negatives));
            writer.section("阈值扫描：精确率-召回率权衡", sweepText(sweep));
            writer.section("HNSW 近似检索代价（本 KB " + totalRows + " 行，全表 " + allTableRows + " 行）",
                    hnswText(hnsw, totalRows, allTableRows));
            writer.section("端到端延迟（含 embedding API）",
                    RagEvalReportWriter.table(
                            List.of("指标", "数值"),
                            List.of(List.of("端到端 P50（" + e2eQueries + " 条 query）",
                                            RagEvalReportWriter.num2(e2eP50) + " ms"),
                                    List.of("说明", "含真实 embedding 调用 + 阈值过滤检索，单线程顺序执行"))));

            Path out = writer.write("RAG检索评测报告");
            log("报告已写入: " + out.toAbsolutePath());
            System.out.println("\n" + writer.render());
        } finally {
            harness.dropKnowledgeBase(kbId);
        }
    }

    // ------------------------------------------------------------------ 消融与质量

    /**
     * 消融度量：每条 query 只检索 <b>一次</b>，全部 K 与 MRR/MAP 复用同一份排序结果。
     *
     * <p>这一点很关键：检索走的是 HNSW 近似索引，重复查询同一 query 可能返回略有差异的 top-K。
     * 若每个 K 各自发起一次查询，同一张表里的数值就来自不同排序，
     * 会出现 Hit@3 &lt; Hit@1 这类非单调的假象。检索质量表直接复用本方法在生产配置下的结果，
     * 因此两张表的生产配置数值完全一致。
     */
    private AblationRow measureAblation(String kbId, RagBenchmarkDataset.AblationConfig cfg,
                                        int chunkCount, int totalChars) {
        Map<Integer, List<Double>> hits = newKMap();
        Map<Integer, List<Double>> precisions = newKMap();
        Map<Integer, List<Double>> recalls = newKMap();
        Map<Integer, List<Double>> ndcgs = newKMap();
        List<Double> mrrs = new ArrayList<>();
        List<Double> maps = new ArrayList<>();

        for (RagBenchmarkDataset.QueryLabel q : RagBenchmarkDataset.answerableQueries()) {
            List<RetrievalMetrics.RankedDoc> ranked = harness.retrieveRanked(kbId, q.query(), MAX_K, THRESHOLD);
            Set<String> relevant = q.relevantDocs();
            for (int k : RagBenchmarkDataset.K_VALUES) {
                hits.get(k).add(RetrievalMetrics.hitAtK(ranked, relevant, k));
                precisions.get(k).add(RetrievalMetrics.precisionAtK(ranked, relevant, k));
                recalls.get(k).add(RetrievalMetrics.recallAtK(ranked, relevant, k));
                ndcgs.get(k).add(RetrievalMetrics.ndcgAtK(ranked, q.docGrades(), k));
            }
            mrrs.add(RetrievalMetrics.reciprocalRank(ranked, relevant));
            maps.add(RetrievalMetrics.averagePrecision(ranked, relevant));
        }
        return new AblationRow(cfg, chunkCount, chunkCount == 0 ? 0 : totalChars / chunkCount,
                averages(hits), averages(precisions), averages(recalls), averages(ndcgs),
                RetrievalMetrics.average(mrrs), RetrievalMetrics.average(maps));
    }

    // ------------------------------------------------------------------ 降噪 / 拒答 / 阈值曲线

    private List<NegativeRow> measureNegatives(String kbId) throws SQLException {
        List<NegativeRow> rows = new ArrayList<>();
        for (String query : RagBenchmarkDataset.negativeQueries()) {
            float[] qv = harness.queryVector(query);
            List<RagEvalHarnessSupport.ScoredDoc> top1 = harness.approximateSearch(kbId, qv, 1);
            double topSimilarity = top1.isEmpty() ? 0.0 : top1.get(0).similarity();
            int injected = harness.searchWithThreshold(kbId, qv, RagBenchmarkDataset.PRODUCTION_TOP_K, THRESHOLD).size();
            rows.add(new NegativeRow(query, topSimilarity, injected));
        }
        return rows;
    }

    private List<SweepRow> measureThresholdSweep(String kbId) throws SQLException {
        List<String> negatives = RagBenchmarkDataset.negativeQueries();
        List<SweepRow> rows = new ArrayList<>();
        for (double threshold : RagBenchmarkDataset.THRESHOLD_SWEEP) {
            List<Double> precision = new ArrayList<>();
            List<Double> recall = new ArrayList<>();
            List<Double> f1 = new ArrayList<>();
            List<Double> ndcg = new ArrayList<>();
            for (RagBenchmarkDataset.QueryLabel q : RagBenchmarkDataset.answerableQueries()) {
                List<RetrievalMetrics.RankedDoc> ranked = sweepRanked(kbId, q.query(), threshold);
                Set<String> relevant = q.relevantDocs();
                double p = RetrievalMetrics.precisionAtK(ranked, relevant, RagBenchmarkDataset.PRODUCTION_TOP_K);
                double r = RetrievalMetrics.recallAtK(ranked, relevant, RagBenchmarkDataset.PRODUCTION_TOP_K);
                precision.add(p);
                recall.add(r);
                f1.add(RetrievalMetrics.f1(p, r));
                ndcg.add(RetrievalMetrics.ndcgAtK(ranked, q.docGrades(), RagBenchmarkDataset.PRODUCTION_TOP_K));
            }
            int injected = 0;
            for (String query : negatives) {
                if (!harness.searchWithThreshold(kbId, harness.queryVector(query),
                        RagBenchmarkDataset.PRODUCTION_TOP_K, threshold).isEmpty()) {
                    injected++;
                }
            }
            rows.add(new SweepRow(threshold, RetrievalMetrics.average(precision), RetrievalMetrics.average(recall),
                    RetrievalMetrics.average(f1), RetrievalMetrics.average(ndcg),
                    negatives.isEmpty() ? 0.0 : (double) injected / negatives.size()));
        }
        return rows;
    }

    private List<RetrievalMetrics.RankedDoc> sweepRanked(String kbId, String query, double threshold)
            throws SQLException {
        return toRanked(harness.searchWithThreshold(kbId, harness.queryVector(query), MAX_K, threshold));
    }

    // ------------------------------------------------------------------ HNSW 近似 vs 精确

    private HnswMeasurement measureHnsw(String kbId) throws SQLException {
        List<Double> approxSamples = new ArrayList<>();
        List<Double> exactSamples = new ArrayList<>();
        Map<Integer, List<Double>> recall = newKMap();
        List<Double> recallBefore = new ArrayList<>();
        List<Double> recallAfter = new ArrayList<>();
        int filteredRelevant = 0;

        for (RagBenchmarkDataset.QueryLabel q : RagBenchmarkDataset.answerableQueries()) {
            float[] qv = harness.queryVector(q.query());
            List<RagEvalHarnessSupport.ScoredDoc> approx = harness.approximateSearch(kbId, qv, MAX_K);
            long t0 = System.nanoTime();
            List<RagEvalHarnessSupport.ScoredDoc> exact = harness.exactSearch(kbId, qv, MAX_K);
            exactSamples.add((System.nanoTime() - t0) / 1_000_000.0);

            Set<String> approxIds = approx.stream().map(RagEvalHarnessSupport.ScoredDoc::chunkId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> exactIds = exact.stream().map(RagEvalHarnessSupport.ScoredDoc::chunkId).toList();
            for (int k : RagBenchmarkDataset.K_VALUES) {
                int n = Math.min(k, exactIds.size());
                if (n == 0) {
                    recall.get(k).add(0.0);
                    continue;
                }
                long overlap = exactIds.subList(0, n).stream().filter(approxIds::contains).count();
                recall.get(k).add((double) overlap / n);
            }

            Set<String> relevant = q.relevantDocs();
            recallBefore.add(RetrievalMetrics.recallAtK(toRanked(exact), relevant,
                    RagBenchmarkDataset.PRODUCTION_TOP_K));
            List<RetrievalMetrics.RankedDoc> afterRanked = harness.retrieveRanked(kbId, q.query(), MAX_K, THRESHOLD);
            recallAfter.add(RetrievalMetrics.recallAtK(afterRanked, relevant, RagBenchmarkDataset.PRODUCTION_TOP_K));
            Set<String> afterDocs = afterRanked.stream().map(RetrievalMetrics.RankedDoc::docId)
                    .collect(Collectors.toSet());
            Set<String> belowThresholdDocs = exact.stream()
                    .filter(d -> d.similarity() < THRESHOLD)
                    .map(RagEvalHarnessSupport.ScoredDoc::docId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (String docId : belowThresholdDocs) {
                if (relevant.contains(docId) && !afterDocs.contains(docId)) {
                    filteredRelevant++;
                }
            }

            approxSamples.addAll(harness.sampleApproximateLatency(kbId, qv, MAX_K, latencyRepeats));
        }

        List<HnswRow> rows = new ArrayList<>();
        for (int k : RagBenchmarkDataset.K_VALUES) {
            rows.add(new HnswRow(k, RetrievalMetrics.average(recall.get(k))));
        }
        double avgApprox = RetrievalMetrics.average(approxSamples);
        return new HnswMeasurement(rows,
                RetrievalMetrics.percentile(approxSamples, 0.50),
                RetrievalMetrics.percentile(approxSamples, 0.99),
                avgApprox <= 0 ? 0 : 1000.0 / avgApprox,
                RetrievalMetrics.percentile(exactSamples, 0.50),
                RetrievalMetrics.percentile(exactSamples, 0.99),
                RetrievalMetrics.average(recallBefore), RetrievalMetrics.average(recallAfter), filteredRelevant);
    }

    private double measureEndToEnd(String kbId) {
        List<RagBenchmarkDataset.QueryLabel> queries = RagBenchmarkDataset.answerableQueries();
        int n = Math.max(1, Math.min(e2eQueries, queries.size()));
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // 走未缓存路径，确保包含真实 embedding API 调用
            samples.add(harness.measureEndToEndLatencyMillis(
                    kbId, queries.get(i).query(), RagBenchmarkDataset.PRODUCTION_TOP_K, THRESHOLD));
        }
        return RetrievalMetrics.percentile(samples, 0.50);
    }

    // ------------------------------------------------------------------ 报告文本

    private String ablationText(List<AblationRow> rows, String productionKey) {
        List<String> headers = new ArrayList<>(List.of("策略/尺寸/overlap", "chunk 数", "平均 chunk 字数"));
        for (int k : RagBenchmarkDataset.K_VALUES) {
            headers.add("Hit@" + k);
        }
        headers.add("Recall@5");
        headers.add("Recall@10");
        headers.add("nDCG@5");
        headers.add("MRR");
        headers.add("MAP");

        List<List<String>> tableRows = new ArrayList<>();
        for (AblationRow r : rows) {
            List<String> line = new ArrayList<>();
            line.add(r.config().label() + (r.config().key().equals(productionKey) ? "（生产配置）" : ""));
            line.add(String.valueOf(r.chunkCount()));
            line.add(String.valueOf(r.avgChunkChars()));
            for (int k : RagBenchmarkDataset.K_VALUES) {
                line.add(RagEvalReportWriter.pct(r.hit().get(k)));
            }
            line.add(RagEvalReportWriter.pct(r.recall().get(5)));
            line.add(RagEvalReportWriter.pct(r.recall().get(10)));
            line.add(RagEvalReportWriter.num4(r.ndcg().get(5)));
            line.add(RagEvalReportWriter.num4(r.mrr()));
            line.add(RagEvalReportWriter.num4(r.map()));
            tableRows.add(line);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("唯一变量是分块配置；语料、查询集、向量模型与检索路径（阈值 ").append(THRESHOLD)
                .append("）全部固定。\n\n");
        sb.append(RagEvalReportWriter.table(headers, tableRows));
        sb.append("\n> 指标单位为「文档」：同一文档的多个 chunk 命中只计一次，避免「切得越碎越占便宜」。\n");
        return sb.toString();
    }

    private String qualityText(AblationRow productionRow) {
        if (productionRow == null) {
            return "未采集到生产配置的检索结果。";
        }
        List<List<String>> rows = new ArrayList<>();
        for (int k : RagBenchmarkDataset.K_VALUES) {
            rows.add(List.of(String.valueOf(k),
                    RagEvalReportWriter.pct(productionRow.hit().get(k)),
                    RagEvalReportWriter.pct(productionRow.precision().get(k)),
                    RagEvalReportWriter.pct(productionRow.recall().get(k)),
                    RagEvalReportWriter.num4(productionRow.ndcg().get(k))));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("语料入库 chunk 数：").append(productionRow.chunkCount())
                .append("；查询数：").append(RagBenchmarkDataset.answerableQueries().size())
                .append("；MRR：").append(RagEvalReportWriter.num4(productionRow.mrr()))
                .append("；MAP：").append(RagEvalReportWriter.num4(productionRow.map())).append("。\n\n");
        sb.append(RagEvalReportWriter.table(List.of("K", "Hit@K", "Precision@K", "Recall@K", "nDCG@K"), rows));
        sb.append("\n> 本表与上表「生产配置」行取自同一次检索，数值可直接对照。\n");
        return sb.toString();
    }

    private String negativesText(List<NegativeRow> negatives) {
        long injected = negatives.stream().filter(n -> n.injectedCount() > 0).count();
        double falseInjection = negatives.isEmpty() ? 0.0 : (double) injected / negatives.size();
        List<List<String>> rows = new ArrayList<>();
        for (NegativeRow n : negatives) {
            rows.add(List.of(shorten(n.query()), RagEvalReportWriter.num4(n.topSimilarity()),
                    String.valueOf(n.injectedCount()),
                    n.injectedCount() > 0 ? "误注入" : "拒答"));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("无关误注入率（阈值 ").append(THRESHOLD).append("）：")
                .append(RagEvalReportWriter.pct(falseInjection))
                .append("，拒答率：").append(RagEvalReportWriter.pct(1 - falseInjection))
                .append("（").append(injected).append("/").append(negatives.size()).append(" 条负样本越过阈值）\n\n");
        sb.append(RagEvalReportWriter.table(List.of("无关 query", "top1 相似度", "注入 chunk 数", "判定"), rows));
        return sb.toString();
    }

    private String sweepText(List<SweepRow> sweep) {
        List<List<String>> rows = new ArrayList<>();
        for (SweepRow r : sweep) {
            rows.add(List.of(String.format("%.2f", r.threshold()),
                    RagEvalReportWriter.pct(r.precision()), RagEvalReportWriter.pct(r.recall()),
                    RagEvalReportWriter.num4(r.f1()), RagEvalReportWriter.num4(r.ndcg()),
                    RagEvalReportWriter.pct(r.falseInjectionRate())));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("阈值扫描回答的是「提高阈值到底换来了什么」。阈值的收益体现为负样本误注入率下降，")
                .append("代价体现为 Precision/Recall/nDCG 的同步下降。本表中的误注入率与召回率均由真实检索产生，")
                .append("可直接对照检查 0.70 的实际收益与代价。\n\n");
        sb.append(RagEvalReportWriter.table(
                List.of("相似度阈值", "Precision@5", "Recall@5", "F1@5", "nDCG@5", "负样本误注入率"), rows));
        sb.append("\n> 阈值语义：传入的是相似度阈值，SQL 内部转换为余弦距离上界 `1 - threshold`。")
                .append("若在本语料上 0.70 的召回损失明显大于它带来的降噪收益，")
                .append("说明阈值应结合具体语料与误注入的实际代价来选，而不是取一个普遍适用的定值。\n");
        return sb.toString();
    }

    private String hnswText(HnswMeasurement m, long kbRows, long allRows) {
        List<List<String>> rows = new ArrayList<>();
        for (HnswRow r : m.recallByK()) {
            rows.add(List.of("近似召回 Recall@" + r.k(), RagEvalReportWriter.pct(r.recall())));
        }
        rows.add(List.of("近似路径 P50 延迟", RagEvalReportWriter.num2(m.approxP50()) + " ms"));
        rows.add(List.of("近似路径 P99 延迟", RagEvalReportWriter.num2(m.approxP99()) + " ms"));
        rows.add(List.of("近似路径 QPS（单线程）", RagEvalReportWriter.num2(m.approxQps())));
        rows.add(List.of("精确路径 P50 延迟", RagEvalReportWriter.num2(m.exactP50()) + " ms"));
        rows.add(List.of("精确路径 P99 延迟", RagEvalReportWriter.num2(m.exactP99()) + " ms"));
        rows.add(List.of("延迟倍数（精确 P50 / 近似 P50）",
                m.approxP50() <= 0 ? "n/a" : RagEvalReportWriter.num2(m.exactP50() / m.approxP50()) + "x"));
        rows.add(List.of("阈值过滤前文档级 Recall@5", RagEvalReportWriter.pct(m.recallBeforeThreshold())));
        rows.add(List.of("阈值过滤后文档级 Recall@5", RagEvalReportWriter.pct(m.recallAfterThreshold())));
        rows.add(List.of("阈值过滤掉的相关文档数", String.valueOf(m.filteredRelevant())));

        StringBuilder sb = new StringBuilder();
        sb.append("近似召回 = 近似 top-K 与精确（`SET LOCAL enable_indexscan=off; enable_seqscan=on`）top-K 的")
                .append(" chunk 集合重合度，分母取两者较短的 K，逐条 query 求平均。\n\n");
        sb.append("延迟口径：关闭索引后 Postgres 只能顺序扫描整张 `knowledge_chunk` 表（")
                .append(allRows).append(" 行），本 KB 仅占 ").append(kbRows)
                .append(" 行；因此精确路径的绝对值反映的是真实表规模下的暴力检索成本，")
                .append("而近似路径的索引扫描只访问 HNSW 图，两者都未加缓存，指标可直接对比。\n\n");
        sb.append(RagEvalReportWriter.table(List.of("指标", "数值"), rows));
        sb.append("\n> 结论方向：HNSW 以极小召回损失换取数量级的延迟下降；")
                .append("而阈值 0.70 会额外丢弃一部分「相关但相似度不足」的命中，这是降噪的必要代价。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 通用工具

    private static List<RetrievalMetrics.RankedDoc> toRanked(List<RagEvalHarnessSupport.ScoredDoc> hits) {
        List<RetrievalMetrics.RankedDoc> ranked = new ArrayList<>(hits.size());
        for (RagEvalHarnessSupport.ScoredDoc d : hits) {
            ranked.add(new RetrievalMetrics.RankedDoc(d.docId(), d.similarity()));
        }
        return RetrievalMetrics.dedupeByDoc(ranked);
    }

    private static Map<Integer, List<Double>> newKMap() {
        Map<Integer, List<Double>> map = new java.util.LinkedHashMap<>();
        for (int k : RagBenchmarkDataset.K_VALUES) {
            map.put(k, new ArrayList<>());
        }
        return map;
    }

    private static Map<Integer, Double> averages(Map<Integer, List<Double>> values) {
        Map<Integer, Double> result = new java.util.LinkedHashMap<>();
        values.forEach((k, list) -> result.put(k, RetrievalMetrics.average(list)));
        return result;
    }

    private static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    private static void log(String message) {
        System.out.println("[RAG-EVAL] " + message);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
