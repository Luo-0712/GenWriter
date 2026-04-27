package com.example.genwriter.service.impl;

import com.example.genwriter.config.EmbeddingConfig;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.model.entity.KnowledgeBase;
import com.example.genwriter.model.entity.KnowledgeChunk;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
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

        if (embeddingConfig.isUseDbVectorSearch()) {
            // 使用 pgvector 数据库级向量搜索 (欧氏距离 <->)
            String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);
            List<KnowledgeChunk> results = knowledgeChunkMapper.similaritySearchWithThreshold(
                    request.getKbId(), vectorLiteral, 1 - threshold, limit);

            return results.stream()
                    .map(this::convertToDTO)
                    .collect(Collectors.toList());
        }

        // 回退到内存计算
        List<KnowledgeChunk> allChunks = knowledgeChunkMapper.selectByKbId(request.getKbId());

        List<SimilarityScore> scoredChunks = allChunks.stream()
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
