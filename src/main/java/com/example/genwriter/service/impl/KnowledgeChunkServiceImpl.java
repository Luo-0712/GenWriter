package com.example.genwriter.service.impl;

import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.model.entity.KnowledgeBase;
import com.example.genwriter.model.entity.KnowledgeChunk;
import com.example.genwriter.retrieval.gate.GateDecision;
import com.example.genwriter.retrieval.gate.RelevanceGate;
import com.example.genwriter.retrieval.gate.RetrievalGateProperties;
import com.example.genwriter.retrieval.rerank.RerankCandidate;
import com.example.genwriter.retrieval.rerank.RerankProperties;
import com.example.genwriter.retrieval.rerank.RerankResult;
import com.example.genwriter.retrieval.rerank.Reranker;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 知识片段服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;
    private final Reranker reranker;
    private final RelevanceGate relevanceGate;
    private final RetrievalGateProperties gateProperties;
    private final RerankProperties rerankProperties;
    private final LongTermMemoryProbeRecorder probeRecorder;

    @Override
    @Transactional
    public KnowledgeChunkDTO createChunk(CreateKnowledgeChunkRequest request) {
        log.debug("创建知识片段: kbId={}", request.getKbId());

        // 验证知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(request.getKbId());
        if (kb == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 复用已有嵌入或生成新的
        float[] embedding = request.getEmbedding() != null
                ? request.getEmbedding()
                : embeddingService.embed(request.getContent());

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .kbId(request.getKbId())
                .sourceId(request.getSourceId())
                .content(request.getContent())
                .embedding(embedding)
                .embeddingDimension(embedding.length)
                .embeddingModel(StringUtils.hasText(request.getEmbeddingModel()) ?
                        request.getEmbeddingModel() : embeddingConfig.getDefaultModel())
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = knowledgeChunkMapper.insert(chunk);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(chunk);
    }

    @Override
    @Transactional
    public List<KnowledgeChunkDTO> createChunks(List<CreateKnowledgeChunkRequest> requests) {
        log.debug("批量创建知识片段: {} 个", requests.size());

        // 分离已有嵌入和需要生成的请求
        List<float[]> embeddings = IntStream.range(0, requests.size())
                .mapToObj(i -> {
                    CreateKnowledgeChunkRequest req = requests.get(i);
                    return req.getEmbedding() != null ? req.getEmbedding() : null;
                })
                .collect(Collectors.toList());

        // 收集需要生成嵌入的文本
        List<String> textsToEmbed = IntStream.range(0, requests.size())
                .filter(i -> embeddings.get(i) == null)
                .mapToObj(i -> requests.get(i).getContent())
                .collect(Collectors.toList());

        // 批量生成缺失的嵌入
        if (!textsToEmbed.isEmpty()) {
            List<float[]> generated = embeddingService.embed(textsToEmbed);
            int genIndex = 0;
            for (int i = 0; i < embeddings.size(); i++) {
                if (embeddings.get(i) == null) {
                    embeddings.set(i, generated.get(genIndex++));
                }
            }
        }

        List<KnowledgeChunk> chunks = IntStream.range(0, requests.size())
                .mapToObj(index -> {
                    CreateKnowledgeChunkRequest req = requests.get(index);
                    float[] embedding = embeddings.get(index);
                    return KnowledgeChunk.builder()
                            .kbId(req.getKbId())
                            .sourceId(req.getSourceId())
                            .content(req.getContent())
                            .embedding(embedding)
                            .embeddingDimension(embedding.length)
                            .embeddingModel(StringUtils.hasText(req.getEmbeddingModel()) ?
                                    req.getEmbeddingModel() : embeddingConfig.getDefaultModel())
                            .metadata(req.getMetadata())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                })
                .collect(Collectors.toList());

        // 批量插入
        for (KnowledgeChunk chunk : chunks) {
            knowledgeChunkMapper.insert(chunk);
        }

        return chunks.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeChunkDTO getChunkById(String id) {
        KnowledgeChunk chunk = knowledgeChunkMapper.selectById(id);
        if (chunk == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_CHUNK_NOT_FOUND);
        }
        return convertToDTO(chunk);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeChunkDTO> getChunksByKbId(String kbId) {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectByKbId(kbId);
        return chunks.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeChunkDTO> searchSimilarChunks(SearchKnowledgeChunkRequest request) {
        log.debug("相似度搜索: kbId={}, query={}", request.getKbId(), request.getQuery());

        // 1. 将查询转换为嵌入向量
        float[] queryEmbedding = embeddingService.embed(request.getQuery());

        double threshold = request.getThreshold() != null ? request.getThreshold() : embeddingConfig.getSimilarityThreshold();
        int limit = request.getLimit() != null ? request.getLimit() : embeddingConfig.getDefaultSearchLimit();

        // 2. rerank 精排路径：宽召回（不做阈值过滤，阈值判定下沉到 gate）+ 精排 + 门控
        List<KnowledgeChunkDTO> rerankedResults = rerankAndGate(request, queryEmbedding, limit);
        if (rerankedResults != null) {
            return rerankedResults;
        }

        // 3. 降级 / 未启用 rerank：原阈值路径（保持既有行为）
        if (embeddingConfig.isUseDbVectorSearch()) {
            // 使用 pgvector 数据库级向量搜索 (余弦距离 <=>)
            // 阈值语义转换: 余弦距离 = 1 - 余弦相似度, 故 1 - threshold 对齐 cosine_dist < 阈值
            // 只检索与查询向量同一 embedding_model 的行，不同模型的向量不可直接比较
            String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);
            List<KnowledgeChunk> results = knowledgeChunkMapper.similaritySearchWithThreshold(
                    request.getKbId(), vectorLiteral, embeddingConfig.getDefaultModel(),
                    1 - threshold, limit);

            return results.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());
        }

        // 回退到内存计算（同样只比较同一 embedding_model 的行）
        List<KnowledgeChunk> allChunks = knowledgeChunkMapper.selectByKbId(request.getKbId());

        List<SimilarityScore> scoredChunks = allChunks.stream()
                .filter(chunk -> embeddingConfig.getDefaultModel()
                        .equalsIgnoreCase(chunk.getEmbeddingModel()))
                .map(chunk -> {
                    float[] chunkVector = chunk.getEmbedding();
                    double similarity = VectorUtils.cosineSimilarity(queryEmbedding, chunkVector);
                    return new SimilarityScore(chunk, similarity);
                })
                .filter(score -> score.similarity() >= threshold)
                .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                .limit(limit)
                .collect(Collectors.toList());

        return scoredChunks.stream()
                .map(SimilarityScore::chunk)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 宽召回 + cross-encoder 精排 + 相关性闸门。
     *
     * @return null 表示 rerank 不可用或调用失败（调用方回退原阈值路径）；
     *         空列表表示门控拒绝（整段不注入，空召回语义）
     */
    private List<KnowledgeChunkDTO> rerankAndGate(SearchKnowledgeChunkRequest request,
                                                  float[] queryEmbedding,
                                                  int limit) {
        if (!reranker.isAvailable()) {
            return null;
        }

        try {
            List<KnowledgeChunk> wideCandidates = wideRecall(request.getKbId(), queryEmbedding,
                    rerankProperties.getRecallLimit());
            if (wideCandidates.isEmpty()) {
                // 宽召回为空走原路径兜底（保持原空结果语义）
                return List.of();
            }

            List<RerankCandidate> candidates = wideCandidates.stream()
                    .map(chunk -> new RerankCandidate(chunk.getId(), chunk.getContent()))
                    .toList();
            List<RerankResult> reranked = reranker.rerank(request.getQuery(), candidates, limit);
            if (reranked.isEmpty()) {
                return null;
            }

            GateDecision decision = relevanceGate.evaluate(
                    reranked.stream().map(RerankResult::score).collect(Collectors.toList()));
            recordGateDecision(decision, reranked.size());
            if (!decision.pass()) {
                return List.of();
            }

            Map<String, KnowledgeChunk> chunkById = wideCandidates.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk, (a, b) -> a, LinkedHashMap::new));
            List<KnowledgeChunkDTO> ordered = new ArrayList<>();
            for (RerankResult result : reranked) {
                KnowledgeChunk chunk = chunkById.get(result.id());
                if (chunk != null) {
                    ordered.add(convertToDTO(chunk));
                }
            }
            return ordered;
        } catch (Exception e) {
            // 降级优先于精度：任何异常回退原阈值路径，不阻断主链路
            log.warn("[RAG] rerank 精排失败，降级为原排序: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 宽召回：按 embedding_model 过滤（跨模型向量不可比），不做阈值过滤，取 top recallLimit。
     */
    private List<KnowledgeChunk> wideRecall(String kbId, float[] queryEmbedding, int recallLimit) {
        if (embeddingConfig.isUseDbVectorSearch()) {
            String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);
            return knowledgeChunkMapper.similaritySearch(
                    kbId, vectorLiteral, embeddingConfig.getDefaultModel(), Math.max(recallLimit, 1));
        }

        // 内存路径：全量计算余弦相似度后取 top N（无阈值过滤）
        return knowledgeChunkMapper.selectByKbId(kbId).stream()
                .filter(chunk -> embeddingConfig.getDefaultModel().equalsIgnoreCase(chunk.getEmbeddingModel()))
                .map(chunk -> new SimilarityScore(chunk,
                        VectorUtils.cosineSimilarity(queryEmbedding, chunk.getEmbedding())))
                .sorted(Comparator.comparingDouble(SimilarityScore::similarity).reversed())
                .limit(Math.max(recallLimit, 1))
                .map(SimilarityScore::chunk)
                .collect(Collectors.toList());
    }

    private void recordGateDecision(GateDecision decision, int candidateCount) {
        try {
            probeRecorder.recordGateDecision(null, "rag", decision.pass(),
                    decision.topScore(), decision.margin(), decision.rejectReason(), candidateCount);
        } catch (Exception e) {
            log.debug("记录门控探针失败: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public void deleteChunk(String id) {
        log.debug("删除知识片段: {}", id);

        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_CHUNK_NOT_FOUND);
        }

        int result = knowledgeChunkMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public void deleteChunksByKbId(String kbId) {
        log.debug("删除知识库的所有片段: {}", kbId);

        int result = knowledgeChunkMapper.deleteByKbId(kbId);
        log.debug("成功删除 {} 个片段", result);
    }

    @Override
    @Transactional
    public void deleteChunksBySourceId(String sourceId) {
        log.debug("删除源文档的所有片段: {}", sourceId);

        int result = knowledgeChunkMapper.deleteBySourceId(sourceId);
        log.debug("成功删除 {} 个片段", result);
    }

    /**
     * 相似度分数辅助类
     */
    private record SimilarityScore(KnowledgeChunk chunk, double similarity) {}

    private KnowledgeChunkDTO convertToDTO(KnowledgeChunk chunk) {
        return KnowledgeChunkDTO.builder()
                .id(chunk.getId())
                .kbId(chunk.getKbId())
                .sourceId(chunk.getSourceId())
                .content(chunk.getContent())
                .embedding(chunk.getEmbedding())
                .embeddingDimension(chunk.getEmbeddingDimension())
                .embeddingModel(chunk.getEmbeddingModel())
                .metadata(chunk.getMetadata())
                .createdAt(chunk.getCreatedAt())
                .updatedAt(chunk.getUpdatedAt())
                .build();
    }
}
