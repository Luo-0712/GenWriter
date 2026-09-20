package com.example.genwriter.rag.eval;

import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.model.entity.KnowledgeBase;
import com.example.genwriter.model.entity.KnowledgeChunk;
import com.example.genwriter.rag.chunking.ChunkingConfig;
import com.example.genwriter.service.DocumentChunkingService;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.service.RAGPipelineService;
import com.example.genwriter.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 评测支撑层：把「生产链路」封装成评测可反复调用的动作。
 *
 * <p>全程复用现网组件，不做任何内存近似替代：
 * <ol>
 *   <li>解析：{@link DocumentChunkingService#chunkDocument} → TikaDocumentLoader（Apache Tika）解析真实文件；</li>
 *   <li>分块：{@code DocumentChunkStrategyFactory} 中的 fixed_size / recursive / structural 真实策略；</li>
 *   <li>向量化：{@link KnowledgeChunkService#createChunks} → {@link EmbeddingService}（真实 API，L2 归一化）；</li>
 *   <li>入库：真实 {@code knowledge_chunk} 表 + pgvector HNSW 索引；</li>
 *   <li>检索：{@link RAGPipelineService#searchAndRetrieve} → {@code KnowledgeChunkServiceImpl#searchSimilarChunks}
 *       的阈值路径（{@code embedding <=> vec < 1 - threshold}），与生产默认路径一致。</li>
 * </ol>
 *
 * <p><b>为什么消融要自己串入库链路</b>：{@code RAGPipelineServiceImpl.processText} 固定使用
 * {@code DocumentChunkingConfig} 的默认 1000/100，无法注入自定义尺寸。因此消融直接组合真实组件
 * （真实分块器 + 真实向量化 + 真实入库），仅绕过被写死的默认参数；检索端仍走完全相同的真实路径。
 */
@Slf4j
public class RagEvalHarnessSupport {

    /** HNSW 索引名（与 db/schema.sql 一致）。 */
    public static final String HNSW_INDEX_NAME = "idx_chunk_embedding_hnsw";

    /** 放大索引时的扰动幅度（高斯噪声标准差）。 */
    private static final double AMPLIFY_JITTER_SIGMA = 0.05;

    private final DocumentChunkingService chunkingService;
    private final KnowledgeChunkService chunkService;
    private final RAGPipelineService ragPipelineService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;
    private final DataSource dataSource;

    /** docId → 原始文件（已落盘到临时目录，供 Tika 解析真实文件）。 */
    private final Map<String, Path> corpusFiles = new LinkedHashMap<>();

    /** source_id(UUID) → docId，用于把检索结果映射回文档。 */
    private final Map<String, String> uuidToDocId = new HashMap<>();

    /** query → 查询向量缓存：消融实验复用同一批 query，避免重复调用 embedding API。 */
    private final Map<String, float[]> queryVectorCache = new HashMap<>();

    public RagEvalHarnessSupport(DocumentChunkingService chunkingService,
                                 KnowledgeChunkService chunkService,
                                 RAGPipelineService ragPipelineService,
                                 KnowledgeBaseMapper knowledgeBaseMapper,
                                 KnowledgeChunkMapper knowledgeChunkMapper,
                                 EmbeddingService embeddingService,
                                 EmbeddingConfig embeddingConfig,
                                 DataSource dataSource) {
        this.chunkingService = chunkingService;
        this.chunkService = chunkService;
        this.ragPipelineService = ragPipelineService;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.embeddingConfig = embeddingConfig;
        this.dataSource = dataSource;
    }

    // ================================================================ 语料准备

    /**
     * 把 classpath 语料落盘到临时目录，使解析链路真实经过 Tika 文件解析（而非直接传字符串）。
     *
     * @return docId → 文件路径
     */
    public Map<String, Path> materializeCorpus(List<RagBenchmarkDataset.CorpusDoc> corpus, Path targetDir)
            throws IOException {
        Files.createDirectories(targetDir);
        corpusFiles.clear();
        uuidToDocId.clear();
        for (RagBenchmarkDataset.CorpusDoc doc : corpus) {
            Path file = targetDir.resolve(doc.fileName());
            Files.writeString(file, doc.text(), StandardCharsets.UTF_8);
            corpusFiles.put(doc.docId(), file);
            uuidToDocId.put(RagBenchmarkDataset.sourceUuidOf(doc.docId()), doc.docId());
        }
        log.info("[RAG-EVAL] 语料已落盘: {} 份 → {}", corpusFiles.size(), targetDir);
        return corpusFiles;
    }

    /** docId → 落盘文件路径。 */
    public Path fileOf(String docId) {
        Path file = corpusFiles.get(docId);
        if (file == null) {
            throw new IllegalStateException("语料未落盘: " + docId);
        }
        return file;
    }

    /** 把 source_id(UUID) 映射回 docId；无法映射时原样返回。 */
    public String docIdOf(String sourceUuid) {
        if (sourceUuid == null) {
            return null;
        }
        return uuidToDocId.getOrDefault(sourceUuid, sourceUuid);
    }

    // ================================================================ 知识库生命周期

    /** 通过真实 DAO 建一个评测专用知识库（与生产数据隔离）。 */
    public String createKnowledgeBase(String name) {
        KnowledgeBase kb = KnowledgeBase.builder()
                .name(name)
                .description("RAG 评测专用知识库，评测结束后自动清理")
                .type("reference")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        int rows = knowledgeBaseMapper.insert(kb);
        if (rows <= 0 || kb.getId() == null) {
            throw new IllegalStateException("创建评测知识库失败");
        }
        log.info("[RAG-EVAL] 创建知识库: name={}, id={}", name, kb.getId());
        return kb.getId();
    }

    /** 删除知识库（knowledge_chunk 通过外键 ON DELETE CASCADE 一并清理）。 */
    public void dropKnowledgeBase(String kbId) {
        if (kbId == null) {
            return;
        }
        try {
            int rows = knowledgeBaseMapper.deleteById(kbId);
            log.info("[RAG-EVAL] 清理知识库: id={}, 影响行数={}", kbId, rows);
        } catch (Exception e) {
            log.warn("[RAG-EVAL] 清理知识库失败: id={}, error={}", kbId, e.getMessage());
        }
    }

    /** 清空某知识库的全部 chunk，用于在同一个知识库上依次跑不同分块配置。 */
    public void truncateChunks(String kbId) {
        knowledgeChunkMapper.deleteByKbId(kbId);
    }

    /** 统计知识库中的 chunk 数。 */
    public long countChunks(String kbId) {
        return knowledgeChunkMapper.countByKbId(kbId);
    }

    /**
     * 统计 {@code knowledge_chunk} 表的全表行数。
     *
     * <p>精确（无索引）检索会顺序扫描整张表，因此该数字是解释精确路径延迟的必要背景。
     */
    public long countAllChunks() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM knowledge_chunk")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ================================================================ 入库（真实链路）

    /** 单份文档的入库结果。 */
    public record IngestResult(String docId, int chunkCount, int totalChars, int avgChunkChars) {
    }

    /**
     * 按指定分块配置把一份文档写入知识库：Tika 解析 → 分块 → 向量化 → 入库。
     *
     * <p>向量化由 {@link KnowledgeChunkService#createChunks} 内部调用 {@link EmbeddingService} 完成，
     * 与 {@code RAGPipelineServiceImpl} 生产写入路径一致。
     */
    public IngestResult ingest(String kbId, String docId, RagBenchmarkDataset.AblationConfig config) {
        String filePath = fileOf(docId).toString();
        ChunkingConfig chunkingConfig = ChunkingConfig.builder()
                .chunkSize(config.chunkSize())
                .chunkOverlap(config.chunkOverlap())
                .keepParagraph(true)
                .keepSentences(true)
                .sourceDocumentId(docId)
                .build();

        List<DocumentChunk> chunks = chunkingService.chunkDocument(filePath, config.strategy(), chunkingConfig);
        List<CreateKnowledgeChunkRequest> requests = new ArrayList<>(chunks.size());
        String sourceUuid = RagBenchmarkDataset.sourceUuidOf(docId);
        int totalChars = 0;
        for (DocumentChunk chunk : chunks) {
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            totalChars += chunk.getContent().length();
            requests.add(CreateKnowledgeChunkRequest.builder()
                    .kbId(kbId)
                    .sourceId(sourceUuid)
                    .content(chunk.getContent())
                    .metadata(String.format("{\"docId\":\"%s\",\"chunkIndex\":%d}", docId, chunk.getIndex()))
                    .build());
        }
        if (requests.isEmpty()) {
            return new IngestResult(docId, 0, 0, 0);
        }
        chunkService.createChunks(requests);
        int avg = (int) Math.round((double) totalChars / requests.size());
        return new IngestResult(docId, requests.size(), totalChars, avg);
    }

    /**
     * 把整份语料按同一配置入库。
     *
     * @return 总 chunk 数
     */
    public int ingestCorpus(String kbId, List<RagBenchmarkDataset.CorpusDoc> corpus,
                            RagBenchmarkDataset.AblationConfig config) {
        int total = 0;
        for (RagBenchmarkDataset.CorpusDoc doc : corpus) {
            total += ingest(kbId, doc.docId(), config).chunkCount();
        }
        log.info("[RAG-EVAL] 语料入库完成: config={}, chunk 总数={}", config.label(), total);
        return total;
    }

    /**
     * 索引规模放大：复用真实语料的真实向量并加入小幅扰动后复制副本，用于在可控成本下
     * 观察 HNSW 在更大索引上的近似召回与延迟表现。
     *
     * <p>相比 {@code RagBenchDriver} 用「高斯随机向量 + 随机拼文本」的做法，这里的数据分布
     * 来自真实 embedding（同一模型、同一维度、同一归一化），查询向量也是真实 query 向量，
     * 因此 HNSW 的邻居结构与延迟量级更接近真实负载。放大后的副本与原始语料同源，
     * 只用于性能/近似召回对比，<b>不参与检索质量与阈值扫描</b>。
     *
     * @param copies 复制份数（总行数 ≈ 原始行数 × (1 + copies)）
     * @return 新插入的行数
     */
    public int amplifyIndex(String kbId, int copies, long seed) {
        List<KnowledgeChunk> base = knowledgeChunkMapper.selectByKbId(kbId);
        if (base.isEmpty()) {
            return 0;
        }
        Random random = new Random(seed);
        int inserted = 0;
        for (int copy = 0; copy < copies; copy++) {
            List<KnowledgeChunk> batch = new ArrayList<>(base.size());
            for (KnowledgeChunk chunk : base) {
                if (chunk.getEmbedding() == null) {
                    continue;
                }
                float[] jittered = jitter(chunk.getEmbedding(), random);
                batch.add(KnowledgeChunk.builder()
                        .kbId(kbId)
                        .sourceId(chunk.getSourceId())
                        .content(chunk.getContent())
                        .embedding(jittered)
                        .embeddingDimension(jittered.length)
                        .embeddingModel(chunk.getEmbeddingModel())
                        .metadata("{\"amplified\":true}")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build());
            }
            if (!batch.isEmpty()) {
                knowledgeChunkMapper.batchInsert(batch);
                inserted += batch.size();
            }
        }
        log.info("[RAG-EVAL] 索引放大完成: kbId={}, 原始行数={}, 新增行数={}", kbId, base.size(), inserted);
        return inserted;
    }

    private static float[] jitter(float[] vector, Random random) {
        float[] noisy = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            noisy[i] = (float) (vector[i] + random.nextGaussian() * AMPLIFY_JITTER_SIGMA);
        }
        return VectorUtils.normalize(noisy);
    }

    // ================================================================ 检索（真实生产路径）

    /**
     * 生产检索路径：{@code RAGPipelineService.searchAndRetrieve}。
     * 默认 {@code retrieval.rerank.enabled=false}，内部走 {@code similaritySearchWithThreshold}
     * 的余弦距离阈值过滤（{@code embedding <=> vec < 1 - threshold}）。
     */
    public List<KnowledgeChunkDTO> retrieveRaw(String kbId, String query, int topK, double threshold) {
        return ragPipelineService.searchAndRetrieve(query, kbId, topK, threshold);
    }

    /**
     * 生产检索路径 + 文档级去重，得到可直接喂给指标库的排序列表。
     *
     * <p>注意：生产阈值 SQL 返回的是 {@code KnowledgeChunk} 实体，距离列不会映射到 DTO 的
     * {@code distance} 字段（实体无该属性），因此这里拿不到真实分数。指标只依赖 <b>排序</b>，
     * 故 score 用「按返回顺序递减的位次分」占位，仅用于报告展示，不参与任何指标计算。
     */
    public List<RetrievalMetrics.RankedDoc> retrieveRanked(String kbId, String query, int topK, double threshold) {
        List<KnowledgeChunkDTO> hits = retrieveRaw(kbId, query, topK, threshold);
        List<RetrievalMetrics.RankedDoc> ranked = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            String docId = docIdOf(hits.get(i).getSourceId());
            ranked.add(new RetrievalMetrics.RankedDoc(docId, 1.0 - (double) i / Math.max(topK, 1)));
        }
        return RetrievalMetrics.dedupeByDoc(ranked);
    }

    // ================================================================ 向量级检索（阈值扫描 / HNSW 对比）

    /**
     * 单条向量检索命中：chunkId + docId + 余弦相似度 + 余弦距离。
     *
     * <p>保留 chunkId 是为了严格度量 HNSW 近似召回（top-K 的 chunk 集合重合度）。
     * 若只保留 docId，同一文档的多个 chunk 会被折叠，近似召回率会被系统性高估。
     */
    public record ScoredDoc(String chunkId, String docId, double similarity, double distance) {
    }

    /** 查询向量缓存（不产生额外 API 调用），供阈值扫描与消融复用。 */
    public float[] queryVector(String query) {
        return queryVectorCache.computeIfAbsent(query, embeddingService::embed);
    }

    /** 直接调用真实 embedding API 生成查询向量（用于度量含 embedding 的端到端延迟）。 */
    public float[] queryVectorUncached(String query) {
        return embeddingService.embed(query);
    }

    /**
     * 走 HNSW 索引的近似 top-N 检索（不带阈值过滤），用于：
     * ① 检索质量上界（排序质量本身）；② 与精确检索对比得到近似召回率；③ 延迟采样。
     */
    public List<ScoredDoc> approximateSearch(String kbId, float[] queryVector, int limit) throws SQLException {
        return vectorSearch(kbId, queryVector, limit, false, false);
    }

    /**
     * 强制顺序扫描的精确 top-N 检索：会话内关闭索引扫描（{@code SET LOCAL enable_indexscan = off}），
     * 得到暴力计算下的「真值」排序，与 HNSW 结果比较即可量化近似召回损失。
     */
    public List<ScoredDoc> exactSearch(String kbId, float[] queryVector, int limit) throws SQLException {
        return vectorSearch(kbId, queryVector, limit, true, false);
    }

    /**
     * 带相似度阈值的 top-N 检索：SQL 与生产 {@code similaritySearchWithThreshold} 等价
     * （同操作符、同 embedding_model 过滤、同 ORDER BY），仅查询向量来自缓存，
     * 用于阈值 0.50~0.80 扫描时避免重复调用 embedding API。
     */
    public List<ScoredDoc> searchWithThreshold(String kbId, float[] queryVector, int topK, double threshold)
            throws SQLException {
        return vectorSearch(kbId, queryVector, topK, false, true, 1 - threshold);
    }

    public List<ScoredDoc> vectorSearch(String kbId, float[] queryVector, int limit, boolean exact, boolean useThreshold)
            throws SQLException {
        return vectorSearch(kbId, queryVector, limit, exact, useThreshold, useThreshold ? 1 - RagBenchmarkDataset.PRODUCTION_THRESHOLD : 0);
    }

    /**
     * 统一的向量检索实现（余弦距离 {@code <=>}）。
     *
     * @param exact        是否禁用索引扫描以强制精确（暴力）检索
     * @param useThreshold 是否附加余弦距离上界过滤
     * @param maxDistance  余弦距离上界（1 - 相似度阈值）
     */
    public List<ScoredDoc> vectorSearch(String kbId, float[] queryVector, int limit, boolean exact,
                                        boolean useThreshold, double maxDistance) throws SQLException {
        String vectorLiteral = VectorUtils.arrayToVectorString(queryVector);
        StringBuilder sql = new StringBuilder(
                "SELECT id::text AS cid, source_id::text AS sid, embedding <=> CAST(? AS vector) AS dist ")
                .append("FROM knowledge_chunk ")
                .append("WHERE kb_id = CAST(? AS uuid) AND embedding_model = ? ");
        if (useThreshold) {
            sql.append("AND embedding <=> CAST(? AS vector) < ? ");
        }
        sql.append("ORDER BY embedding <=> CAST(? AS vector) LIMIT ?");

        try (Connection conn = dataSource.getConnection()) {
            if (exact) {
                conn.setAutoCommit(false);
                try (Statement st = conn.createStatement()) {
                    st.execute("SET LOCAL enable_indexscan = off");
                    st.execute("SET LOCAL enable_bitmapscan = off");
                    st.execute("SET LOCAL enable_seqscan = on");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, vectorLiteral);
                ps.setString(idx++, kbId);
                ps.setString(idx++, embeddingConfig.getDefaultModel());
                if (useThreshold) {
                    ps.setString(idx++, vectorLiteral);
                    ps.setDouble(idx++, maxDistance);
                }
                ps.setString(idx++, vectorLiteral);
                ps.setInt(idx, Math.max(limit, 1));

                List<ScoredDoc> results = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double distance = rs.getDouble("dist");
                        results.add(new ScoredDoc(rs.getString("cid"), docIdOf(rs.getString("sid")),
                                1 - distance, distance));
                    }
                }
                if (exact) {
                    conn.commit();
                    conn.setAutoCommit(true);
                }
                return results;
            } finally {
                if (exact && !conn.getAutoCommit()) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
        }
    }

    /**
     * 纯向量检索延迟采样（不含 embedding）：重复调用近似路径，返回每次耗时（毫秒）。
     */
    public List<Double> sampleApproximateLatency(String kbId, float[] queryVector, int limit, int repeats)
            throws SQLException {
        List<Double> samples = new ArrayList<>(repeats);
        for (int i = 0; i < repeats; i++) {
            long start = System.nanoTime();
            approximateSearch(kbId, queryVector, limit);
            samples.add((System.nanoTime() - start) / 1_000_000.0);
        }
        return samples;
    }

    /** 端到端延迟（含真实 embedding API 调用 + 阈值过滤检索）。 */
    public double measureEndToEndLatencyMillis(String kbId, String query, int topK, double threshold) {
        long start = System.nanoTime();
        retrieveRaw(kbId, query, topK, threshold);
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    // ================================================================ 前置校验

    /**
     * 校验评测前置条件：HNSW 索引存在、pgvector 可用、表结构可访问。
     * 缺少索引时直接失败并给出可执行的修复提示，避免静默退化成顺序扫描导致指标失真。
     */
    public void assertPreconditions() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase().contains("postgres")) {
                throw new IllegalStateException("[RAG-EVAL] 评测需要 PostgreSQL + pgvector，当前数据源为: " + product
                        + "。请确认 spring.datasource.url 指向 jdbc:postgresql://localhost:5432/genwriter_db");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'knowledge_chunk' AND indexname = ?")) {
                ps.setString(1, HNSW_INDEX_NAME);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("[RAG-EVAL] 未找到 HNSW 索引 " + HNSW_INDEX_NAME
                                + "。请先执行 src/main/resources/db/schema.sql 中的向量索引定义：\n"
                                + "CREATE INDEX IF NOT EXISTS " + HNSW_INDEX_NAME
                                + " ON knowledge_chunk USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);");
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT extversion FROM pg_extension WHERE extname = 'vector'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    log.info("[RAG-EVAL] 前置校验通过: pgvector={}, 索引={}, embedding_model={}",
                            rs.getString(1), HNSW_INDEX_NAME, embeddingConfig.getDefaultModel());
                } else {
                    log.warn("[RAG-EVAL] 未检测到 pgvector 扩展，向量检索可能失败");
                }
            }
        }
    }

    /** 当前写入/检索使用的 embedding 模型标识（需与库中 embedding_model 列一致，否则召回为空）。 */
    public String embeddingModelName() {
        return embeddingConfig.getDefaultModel();
    }

    /** 配置中的向量维度。 */
    public int embeddingDimension() {
        return embeddingConfig.getDimension();
    }

    /** 汇总知识库中各 embedding_model 的行数，便于排查模型标识不一致导致的空召回。 */
    public Map<String, Long> countByEmbeddingModel(String kbId) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT embedding_model, COUNT(*) FROM knowledge_chunk WHERE kb_id = CAST(? AS uuid) "
                             + "GROUP BY embedding_model ORDER BY 2 DESC")) {
            ps.setString(1, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getLong(2));
                }
            }
        }
        return counts;
    }

    /** 取出知识库里的全部 source_id，便于把握语料覆盖情况。 */
    public Set<String> distinctDocIds(String kbId) {
        return knowledgeChunkMapper.selectByKbId(kbId).stream()
                .map(chunk -> docIdOf(chunk.getSourceId()))
                .collect(Collectors.toSet());
    }
}
