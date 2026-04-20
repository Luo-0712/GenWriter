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

    @Value("${app.embedding.dimensions.bge-m3:1024}")
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

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .kbId(request.getKbId())
                .sourceId(request.getSourceId())
                .content(request.getContent())
                .embedding(request.getEmbedding())
                .embeddingDimension(request.getEmbeddingDimension() != null ? 
                        request.getEmbeddingDimension() : defaultEmbeddingDimension)
                .embeddingModel(StringUtils.hasText(request.getEmbeddingModel()) ? 
                        request.getEmbeddingModel() : "bge-m3")
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

        List<KnowledgeChunk> chunks = requests.stream()
                .map(req -> KnowledgeChunk.builder()
                        .kbId(req.getKbId())
                        .sourceId(req.getSourceId())
                        .content(req.getContent())
                        .embedding(req.getEmbedding())
                        .embeddingDimension(req.getEmbeddingDimension() != null ? 
                                req.getEmbeddingDimension() : defaultEmbeddingDimension)
                        .embeddingModel(StringUtils.hasText(req.getEmbeddingModel()) ? 
                                req.getEmbeddingModel() : "bge-m3")
                        .metadata(req.getMetadata())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())
                .collect(Collectors.toList());

        // 这里简化处理,实际应该使用批量插入
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

        // 这里简化处理,实际应该调用嵌入服务将query转换为向量
        // 暂时返回空列表或根据配置决定是否抛出异常
        throw new BizException("EMBEDDING_ERROR", "需要集成嵌入服务才能进行相似度搜索");
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
