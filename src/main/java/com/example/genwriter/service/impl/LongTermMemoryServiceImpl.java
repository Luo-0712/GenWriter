package com.example.genwriter.service.impl;

import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.LongTermMemoryMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateMemoryRequest;
import com.example.genwriter.model.dto.request.UpdateMemoryRequest;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.dto.response.PageResult;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.util.VectorUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private final LongTermMemoryMapper mapper;
    private final EmbeddingService embeddingService;
    private final TaskSessionMapper taskSessionMapper;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public List<MemoryVO> retrieveMemories(String query, List<MemoryType> types,
                                           String sessionId) {
        String projectId = resolveProjectId(sessionId);
        float[] queryEmbedding = embeddingService.embed(query);
        String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);

        List<String> typeNames = types.stream().map(MemoryType::name).toList();
        double threshold = 1 - properties.getRetrieval().getSimilarityThreshold();
        int limit = properties.getRetrieval().getMaxResults();

        List<LongTermMemory> results = mapper.similaritySearch(
                vectorLiteral, typeNames, projectId, threshold, limit);

        for (LongTermMemory memory : results) {
            try {
                mapper.updateById(LongTermMemory.builder()
                        .id(memory.getId())
                        .accessCount(memory.getAccessCount() != null ? memory.getAccessCount() + 1 : 1)
                        .lastAccessedAt(LocalDateTime.now())
                        .build());
            } catch (Exception e) {
                log.debug("更新记忆访问统计失败: id={}", memory.getId(), e);
            }
        }

        return results.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public LongTermMemory storeMemory(String content, MemoryType type, String scope,
                                      String projectId,
                                      String sessionId, String importance) {
        float[] embedding = embeddingService.embed(content);
        String vectorLiteral = VectorUtils.arrayToVectorString(embedding);

        LongTermMemory duplicate = mapper.findDuplicate(
                type.name(), scope, projectId, vectorLiteral,
                properties.getRetrieval().getDedupThreshold());

        if (duplicate != null) {
            return mergeMemory(duplicate, content);
        }

        LongTermMemory memory = LongTermMemory.builder()
                .content(content)
                .memoryType(type.name())
                .scope(scope)
                .projectId(projectId)
                .sessionId(sessionId)
                .embedding(embedding)
                .embeddingModel("text-embedding-v4")
                .importance(importance != null ? importance : "MEDIUM")
                .accessCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        mapper.insert(memory);
        log.info("存储长期记忆: type={}, scope={}, id={}", type, scope, memory.getId());
        return memory;
    }

    @Override
    @Transactional
    public void deleteMemory(String id) {
        LongTermMemory existing = mapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.MEMORY_NOT_FOUND);
        }
        mapper.deleteById(id);
    }

    @Override
    @Transactional
    public int deleteMemories(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return mapper.deleteByIds(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public MemoryVO getById(String id) {
        LongTermMemory memory = mapper.selectById(id);
        if (memory == null) {
            throw new BizException(BizException.ErrorCode.MEMORY_NOT_FOUND);
        }
        return convertToVO(memory);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<MemoryVO> listByFilter(String type, String scope, String projectId,
                                             String importance,
                                             String keyword, int page, int size) {
        int offset = (page - 1) * size;
        List<LongTermMemory> items = mapper.selectByFilter(type, scope, projectId,
                importance, keyword, size, offset);
        long total = mapper.countByFilter(type, scope, projectId, importance, keyword);

        List<MemoryVO> vos = items.stream().map(this::convertToVO).collect(Collectors.toList());
        return PageResult.<MemoryVO>builder()
                .items(vos)
                .total(total)
                .page(page)
                .size(size)
                .build();
    }

    @Override
    public List<MemoryVO> searchByQuery(String query, List<String> types, String scope,
                                        String projectId, double threshold, int limit) {
        float[] queryEmbedding = embeddingService.embed(query);
        String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);

        if (threshold <= 0) {
            threshold = 1 - properties.getRetrieval().getSimilarityThreshold();
        }
        if (limit <= 0) {
            limit = 10;
        }

        List<LongTermMemory> results = mapper.similaritySearch(
                vectorLiteral, types, projectId, threshold, limit);

        return results.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MemoryVO createMemory(CreateMemoryRequest request) {
        String scope = request.getScope() != null ? request.getScope() : "GLOBAL";
        String importance = request.getImportance() != null ? request.getImportance() : "MEDIUM";

        validateScopeConsistency(scope, request.getProjectId());

        float[] embedding = embeddingService.embed(request.getContent());
        String vectorLiteral = VectorUtils.arrayToVectorString(embedding);

        LongTermMemory duplicate = mapper.findDuplicate(
                request.getMemoryType(), scope, request.getProjectId(), vectorLiteral,
                properties.getRetrieval().getDedupThreshold());

        if (duplicate != null) {
            throw new BizException(BizException.ErrorCode.MEMORY_DUPLICATE);
        }

        LongTermMemory memory = LongTermMemory.builder()
                .content(request.getContent())
                .memoryType(request.getMemoryType())
                .scope(scope)
                .projectId(request.getProjectId())
                .embedding(embedding)
                .embeddingModel("text-embedding-v4")
                .importance(importance)
                .accessCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        mapper.insert(memory);
        return convertToVO(memory);
    }

    @Override
    @Transactional
    public MemoryVO updateMemory(String id, UpdateMemoryRequest request) {
        LongTermMemory existing = mapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.MEMORY_NOT_FOUND);
        }

        LongTermMemory.LongTermMemoryBuilder builder = LongTermMemory.builder()
                .id(id);

        if (request.getContent() != null) {
            builder.content(request.getContent());
            float[] newEmbedding = embeddingService.embed(request.getContent());
            builder.embedding(newEmbedding);
        } else {
            builder.content(existing.getContent());
        }

        builder.memoryType(request.getMemoryType() != null ? request.getMemoryType() : existing.getMemoryType());
        builder.scope(request.getScope() != null ? request.getScope() : existing.getScope());
        builder.projectId(request.getProjectId() != null ? request.getProjectId() : existing.getProjectId());
        builder.importance(request.getImportance() != null ? request.getImportance() : existing.getImportance());
        builder.embeddingModel(existing.getEmbeddingModel());
        builder.accessCount(existing.getAccessCount());
        builder.lastAccessedAt(existing.getLastAccessedAt());

        mapper.updateById(builder.build());

        LongTermMemory updated = mapper.selectById(id);
        return convertToVO(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryVO> listByType(MemoryType type) {
        return mapper.selectByFilter(type.name(), null, null, null, null,
                        Integer.MAX_VALUE, 0)
                .stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemoryVO> listByProject(String projectId) {
        return mapper.selectByFilter(null, null, projectId, null, null,
                        Integer.MAX_VALUE, 0)
                .stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private String resolveProjectId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            TaskSession session = taskSessionMapper.selectById(sessionId);
            return session != null ? session.getProjectId() : null;
        } catch (Exception e) {
            log.debug("解析projectId失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    private LongTermMemory mergeMemory(LongTermMemory existing, String newContent) {
        String mergedContent = existing.getContent() + "\n---\n" + newContent;
        String mergedImportance = mergeImportance(existing.getImportance(), "MEDIUM");

        LongTermMemory updated = LongTermMemory.builder()
                .id(existing.getId())
                .content(mergedContent)
                .importance(mergedImportance)
                .embedding(existing.getEmbedding())
                .embeddingModel(existing.getEmbeddingModel())
                .accessCount(existing.getAccessCount())
                .lastAccessedAt(existing.getLastAccessedAt())
                .build();

        mapper.updateById(updated);
        log.info("合并长期记忆: id={}, type={}", existing.getId(), existing.getMemoryType());
        return updated;
    }

    private String mergeImportance(String a, String b) {
        if ("HIGH".equals(a) || "HIGH".equals(b)) return "HIGH";
        if ("MEDIUM".equals(a) || "MEDIUM".equals(b)) return "MEDIUM";
        return "LOW";
    }

    private void validateScopeConsistency(String scope, String projectId) {
        if ("PROJECT".equals(scope) && (projectId == null || projectId.isBlank())) {
            throw new BizException(BizException.ErrorCode.MEMORY_SCOPE_INVALID);
        }
    }

    private MemoryVO convertToVO(LongTermMemory memory) {
        return MemoryVO.builder()
                .id(memory.getId())
                .content(memory.getContent())
                .memoryType(memory.getMemoryType())
                .scope(memory.getScope())
                .projectId(memory.getProjectId())
                .sessionId(memory.getSessionId())
                .embeddingModel(memory.getEmbeddingModel())
                .importance(memory.getImportance())
                .metadata(parseMetadata(memory.getMetadata()))
                .accessCount(memory.getAccessCount())
                .lastAccessedAt(memory.getLastAccessedAt())
                .createdAt(memory.getCreatedAt())
                .updatedAt(memory.getUpdatedAt())
                .build();
    }

    private Object parseMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.readValue(metadata, Object.class);
        } catch (Exception e) {
            return metadata;
        }
    }
}
