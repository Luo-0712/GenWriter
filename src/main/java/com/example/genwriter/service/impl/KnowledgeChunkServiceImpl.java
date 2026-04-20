package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.model.entity.KnowledgeBase;
import com.example.genwriter.model.entity.KnowledgeChunk;
import com.example.genwriter.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识片段服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingModel embeddingModel;

    @Value("${app.embedding.dimensions.text-embedding-v1:1536}")
    private int defaultEmbeddingDimension;

    @Override
    @Transactional
    public KnowledgeChunkDTO createChunk(CreateKnowledgeChunkRequest request) {
        log.info("创建知识片段: kbId={}", request.getKbId());

        // 验证知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(request.getKbId());
        if (kb == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        // 生成文本嵌入
        float[] embedding = generateEmbedding(request.getContent());

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .kbId(request.getKbId())
                .sourceId(request.getSourceId())
                .content(request.getContent())
                .embedding(vectorToString(embedding))
                .embeddingDimension(embedding.length)
                .embeddingModel(StringUtils.hasText(request.getEmbeddingModel()) ?
                        request.getEmbeddingModel() : "text-embedding-v1")
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
        log.info("批量创建知识片段: {} 个", requests.size());

        // 批量生成嵌入
        List<String> contents = requests.stream()
                .map(CreateKnowledgeChunkRequest::getContent)
                .collect(Collectors.toList());

        List<float[]> embeddings = batchGenerateEmbeddings(contents);

        List<KnowledgeChunk> chunks = requests.stream()
                .map((req, index) -> KnowledgeChunk.builder()
                        .kbId(req.getKbId())
                        .sourceId(req.getSourceId())
                        .content(req.getContent())
                        .embedding(vectorToString(embeddings.get(index)))
                        .embeddingDimension(embeddings.get(index).length)
                        .embeddingModel(StringUtils.hasText(req.getEmbeddingModel()) ?
                                req.getEmbeddingModel() : "text-embedding-v1")
                        .metadata(req.getMetadata())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
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
        log.info("相似度搜索: kbId={}, query={}", request.getKbId(), request.getQuery());

        // 1. 将查询转换为嵌入向量
        float[] queryEmbedding = generateEmbedding(request.getQuery());

        // 2. 查询所有相关的知识片段
        List<KnowledgeChunk> allChunks = knowledgeChunkMapper.selectByKbId(request.getKbId());

        // 3. 计算相似度并排序
        List<SimilarityScore> scoredChunks = allChunks.stream()
                .map(chunk -> {
                    float[] chunkVector = stringToVector(chunk.getEmbedding());
                    double similarity = calculateCosineSimilarity(queryEmbedding, chunkVector);
                    return new SimilarityScore(chunk, similarity);
                })
                .filter(score -> score.getSimilarity() >= 0.7) // 相似度阈值
                .sorted((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()))
                .limit(request.getTopK() != null ? request.getTopK() : 5)
                .collect(Collectors.toList());

        // 4. 返回排序后的结果
        return scoredChunks.stream()
                .map(SimilarityScore::getChunk)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteChunk(String id) {
        log.info("删除知识片段: {}", id);

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
        log.info("删除知识库的所有片段: {}", kbId);

        int result = knowledgeChunkMapper.deleteByKbId(kbId);
        log.info("成功删除 {} 个片段", result);
    }

    @Override
    @Transactional
    public void deleteChunksBySourceId(String sourceId) {
        log.info("删除源文档的所有片段: {}", sourceId);

        int result = knowledgeChunkMapper.deleteBySourceId(sourceId);
        log.info("成功删除 {} 个片段", result);
    }

    /**
     * 将float数组转换为PostgreSQL向量字符串格式
     */
    /**
     * 生成文本嵌入
     */
    private float[] generateEmbedding(String text) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(List.of(text), null);
            EmbeddingResponse response = embeddingModel.call(request);
            return response.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.error("生成嵌入失败: {}", e.getMessage(), e);
            throw new BizException("EMBEDDING_ERROR", "生成嵌入失败: " + e.getMessage());
        }
    }

    /**
     * 批量生成嵌入
     */
    private List<float[]> batchGenerateEmbeddings(List<String> contents) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(contents, null);
            EmbeddingResponse response = embeddingModel.call(request);
            return response.getResults().stream()
                    .map(EmbeddingResult::getOutput)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("批量生成嵌入失败: {}", e.getMessage(), e);
            throw new BizException("EMBEDDING_ERROR", "批量生成嵌入失败: " + e.getMessage());
        }
    }

    /**
     * 将向量字符串转换为float数组
     */
    private float[] stringToVector(String vectorString) {
        if (vectorString == null || vectorString.isEmpty()) {
            return new float[0];
        }
        // 移除方括号并分割
        String content = vectorString.substring(1, vectorString.length() - 1);
        String[] parts = content.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());
        }
        return vector;
    }

    /**
     * 计算余弦相似度
     */
    private double calculateCosineSimilarity(float[] vecA, float[] vecB) {
        if (vecA.length != vecB.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vecA.length; i++) {
            dotProduct += vecA[i] * vecB[i];
            normA += Math.pow(vecA[i], 2);
            normB += Math.pow(vecB[i], 2);
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 相似度分数辅助类
     */
    private record SimilarityScore(KnowledgeChunk chunk, double similarity) {}

    private String vectorToString(float[] vector) {
        if (vector == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

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
