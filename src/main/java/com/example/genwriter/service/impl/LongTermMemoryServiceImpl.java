package com.example.genwriter.service.impl;

import com.example.genwriter.agent.memory.LongTermMemoryMetadataSupport;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final double VECTOR_WEIGHT = 0.70;
    private static final double KEYWORD_WEIGHT = 0.15;
    private static final double ENTITY_WEIGHT = 0.10;
    private static final double PROJECT_SCOPE_BOOST = 0.06;

    private final LongTermMemoryMapper mapper;
    private final EmbeddingService embeddingService;
    private final TaskSessionMapper taskSessionMapper;
    private final LongTermMemoryProperties properties;
    private final LongTermMemoryMetadataSupport metadataSupport;

    @Override
    public List<MemoryVO> retrieveMemories(String query, List<MemoryType> types,
                                           String sessionId) {
        String projectId = resolveProjectId(sessionId);
        List<String> typeNames = types == null ? List.of() : types.stream().map(MemoryType::name).toList();
        int limit = properties.getRetrieval().getMaxResults();
        double threshold = defaultSearchThreshold();

        List<ScoredMemory> ranked = rankCandidates(query, typeNames, projectId, threshold, limit);
        List<ScoredMemory> selected = diversifyByType(ranked, typeNames, limit);

        for (ScoredMemory scored : selected) {
            LongTermMemory memory = scored.memory;
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

        return selected.stream()
                .map(scored -> {
                    scored.memory.setSimilarity(scored.score);
                    return convertToVO(scored.memory);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public LongTermMemory storeMemory(String content, MemoryType type, String scope,
                                      String projectId,
                                      String sessionId, String importance) {
        return storeMemory(content, type, scope, projectId, sessionId, importance, null);
    }

    @Override
    @Transactional
    public LongTermMemory storeMemory(String content, MemoryType type, String scope,
                                      String projectId,
                                      String sessionId, String importance,
                                      Map<String, Object> metadata) {
        String effectiveScope = scope != null && !scope.isBlank() ? scope : "GLOBAL";
        String effectiveProjectId = "PROJECT".equals(effectiveScope) ? projectId : null;
        String effectiveImportance = importance != null && !importance.isBlank() ? importance : "MEDIUM";
        validateScopeConsistency(effectiveScope, effectiveProjectId);

        Map<String, Object> normalizedMetadata = metadataSupport.normalize(
                content, type.name(), effectiveScope, effectiveProjectId, sessionId, effectiveImportance, metadata);
        String retrievalText = metadataSupport.buildRetrievalText(content, type.name(), normalizedMetadata);
        float[] embedding = embeddingService.embed(retrievalText);
        String vectorLiteral = VectorUtils.arrayToVectorString(embedding);

        LongTermMemory duplicate = mapper.findDuplicate(
                type.name(), effectiveScope, effectiveProjectId, vectorLiteral,
                properties.getRetrieval().getDedupThreshold());

        if (duplicate != null) {
            return mergeMemory(duplicate, content, effectiveImportance, sessionId, normalizedMetadata);
        }

        LongTermMemory memory = LongTermMemory.builder()
                .content(content)
                .memoryType(type.name())
                .scope(effectiveScope)
                .projectId(effectiveProjectId)
                .sessionId(sessionId)
                .embedding(embedding)
                .embeddingModel("text-embedding-v4")
                .importance(effectiveImportance)
                .metadata(metadataSupport.toJson(normalizedMetadata))
                .accessCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        mapper.insert(memory);
        log.info("存储长期记忆: type={}, scope={}, id={}", type, effectiveScope, memory.getId());
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
        // UUID 格式校验，防止非法值传入 PostgreSQL CAST(? AS uuid)
        List<String> validIds = ids.stream()
                .filter(id -> id != null && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                .collect(Collectors.toList());
        if (validIds.isEmpty()) return 0;
        return mapper.deleteByIds(validIds);
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
        int effectiveLimit = limit > 0 ? limit : 10;
        double effectiveThreshold = threshold > 0 ? threshold : defaultSearchThreshold();
        List<String> typeNames = types == null ? List.of() : types.stream()
                .filter(t -> t != null && !t.isBlank())
                .toList();

        List<ScoredMemory> ranked = rankCandidates(query, typeNames, projectId, effectiveThreshold, effectiveLimit);
        if (scope != null && !scope.isBlank()) {
            ranked = ranked.stream()
                    .filter(scored -> scope.equals(scored.memory.getScope()))
                    .toList();
        }

        return diversifyByType(ranked, typeNames, effectiveLimit).stream()
                .map(scored -> {
                    scored.memory.setSimilarity(scored.score);
                    return convertToVO(scored.memory);
                })
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MemoryVO createMemory(CreateMemoryRequest request) {
        String scope = request.getScope() != null ? request.getScope() : "GLOBAL";
        String projectId = "PROJECT".equals(scope) ? request.getProjectId() : null;
        String importance = request.getImportance() != null ? request.getImportance() : "MEDIUM";
        MemoryType memoryType = MemoryType.valueOf(request.getMemoryType());

        validateScopeConsistency(scope, projectId);

        Map<String, Object> normalizedMetadata = metadataSupport.normalize(
                request.getContent(), memoryType.name(), scope, projectId, null, importance, request.getMetadata());
        String retrievalText = metadataSupport.buildRetrievalText(request.getContent(), memoryType.name(), normalizedMetadata);
        float[] embedding = embeddingService.embed(retrievalText);
        String vectorLiteral = VectorUtils.arrayToVectorString(embedding);

        LongTermMemory duplicate = mapper.findDuplicate(
                memoryType.name(), scope, projectId, vectorLiteral,
                properties.getRetrieval().getDedupThreshold());

        if (duplicate != null) {
            throw new BizException(BizException.ErrorCode.MEMORY_DUPLICATE);
        }

        LongTermMemory memory = LongTermMemory.builder()
                .content(request.getContent())
                .memoryType(memoryType.name())
                .scope(scope)
                .projectId(projectId)
                .embedding(embedding)
                .embeddingModel("text-embedding-v4")
                .importance(importance)
                .metadata(metadataSupport.toJson(normalizedMetadata))
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

        String content = request.getContent() != null ? request.getContent() : existing.getContent();
        String memoryType = request.getMemoryType() != null ? request.getMemoryType() : existing.getMemoryType();
        String scope = request.getScope() != null ? request.getScope() : existing.getScope();
        String projectId = request.getProjectId() != null ? request.getProjectId() : existing.getProjectId();
        String importance = request.getImportance() != null ? request.getImportance() : existing.getImportance();
        Object rawMetadata = request.getMetadata() != null ? request.getMetadata() : existing.getMetadata();

        if ("GLOBAL".equals(scope)) {
            projectId = null;
        }
        validateScopeConsistency(scope, projectId);

        Map<String, Object> normalizedMetadata = metadataSupport.normalize(
                content, memoryType, scope, projectId, existing.getSessionId(), importance, rawMetadata);
        String retrievalText = metadataSupport.buildRetrievalText(content, memoryType, normalizedMetadata);
        float[] embedding = embeddingService.embed(retrievalText);

        LongTermMemory updatedMemory = LongTermMemory.builder()
                .id(id)
                .content(content)
                .memoryType(memoryType)
                .scope(scope)
                .projectId(projectId)
                .importance(importance)
                .metadata(metadataSupport.toJson(normalizedMetadata))
                .embedding(embedding)
                .embeddingModel(existing.getEmbeddingModel())
                .accessCount(existing.getAccessCount())
                .lastAccessedAt(existing.getLastAccessedAt())
                .build();

        mapper.updateById(updatedMemory);

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

    private List<ScoredMemory> rankCandidates(String query,
                                              List<String> typeNames,
                                              String projectId,
                                              double threshold,
                                              int finalLimit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int candidateLimit = Math.max(finalLimit * 4, 20);
        List<String> sqlTypes = typeNames == null || typeNames.isEmpty() ? null : typeNames;
        Map<String, ScoredMemory> candidates = new LinkedHashMap<>();

        float[] queryEmbedding = embeddingService.embed(query);
        String vectorLiteral = VectorUtils.arrayToVectorString(queryEmbedding);
        for (LongTermMemory memory : mapper.similaritySearch(
                vectorLiteral, sqlTypes, projectId, threshold, candidateLimit)) {
            mergeCandidate(candidates, memory, false);
        }

        List<String> terms = metadataSupport.extractTextSearchTerms(query);
        if (!terms.isEmpty()) {
            for (LongTermMemory memory : mapper.textSearch(terms, sqlTypes, projectId, candidateLimit)) {
                mergeCandidate(candidates, memory, true);
            }
        }

        return candidates.values().stream()
                .peek(candidate -> candidate.score = calculateScore(candidate, query, terms, projectId))
                .sorted(Comparator.comparingDouble((ScoredMemory candidate) -> candidate.score).reversed())
                .collect(Collectors.toList());
    }

    private void mergeCandidate(Map<String, ScoredMemory> candidates, LongTermMemory memory, boolean textMatched) {
        if (memory == null || memory.getId() == null) {
            return;
        }
        ScoredMemory existing = candidates.get(memory.getId());
        if (existing == null) {
            candidates.put(memory.getId(), new ScoredMemory(memory, textMatched));
            return;
        }

        existing.textMatched = existing.textMatched || textMatched;
        double oldSimilarity = existing.memory.getSimilarity() != null ? existing.memory.getSimilarity() : 0.0;
        double newSimilarity = memory.getSimilarity() != null ? memory.getSimilarity() : 0.0;
        if (newSimilarity > oldSimilarity) {
            existing.memory.setSimilarity(newSimilarity);
        }
    }

    private double calculateScore(ScoredMemory candidate, String query, List<String> terms, String projectId) {
        LongTermMemory memory = candidate.memory;
        Map<String, Object> metadata = metadataSupport.parseMetadata(memory.getMetadata());
        double vectorScore = clamp(memory.getSimilarity() != null ? memory.getSimilarity() : 0.0);
        double keywordScore = keywordScore(memory, metadata, query, terms, candidate.textMatched);
        double entityScore = entityScore(metadata, query, terms);
        double importanceBoost = importanceBoost(memory.getImportance());
        double scopeBoost = "PROJECT".equals(memory.getScope())
                && projectId != null
                && projectId.equals(memory.getProjectId())
                ? PROJECT_SCOPE_BOOST : 0.0;

        return vectorScore * VECTOR_WEIGHT
                + keywordScore * KEYWORD_WEIGHT
                + entityScore * ENTITY_WEIGHT
                + importanceBoost
                + scopeBoost;
    }

    private double keywordScore(LongTermMemory memory,
                                Map<String, Object> metadata,
                                String query,
                                List<String> terms,
                                boolean textMatched) {
        String searchable = normalizeSearchText(memory.getContent() + " " + metadata);
        int hits = 0;
        for (String term : terms) {
            if (searchable.contains(normalizeSearchText(term))) {
                hits++;
            }
        }
        double ratio = terms.isEmpty() ? 0.0 : Math.min(1.0, hits / (double) Math.min(4, terms.size()));
        if (!query.isBlank() && searchable.contains(normalizeSearchText(query))) {
            ratio = Math.max(ratio, 1.0);
        }
        if (textMatched) {
            ratio = Math.max(ratio, 0.35);
        }
        return ratio;
    }

    private double entityScore(Map<String, Object> metadata, String query, List<String> terms) {
        String queryText = normalizeSearchText(query);
        List<String> entities = new ArrayList<>(metadataSupport.toStringList(metadata.get("entities")));
        String title = asString(metadata.get("title"));
        if (title != null) {
            entities.add(title);
        }

        for (String entity : entities) {
            String normalizedEntity = normalizeSearchText(entity);
            if (normalizedEntity.length() < 2) {
                continue;
            }
            if (queryText.contains(normalizedEntity)) {
                return 1.0;
            }
            for (String term : terms) {
                String normalizedTerm = normalizeSearchText(term);
                if (normalizedEntity.contains(normalizedTerm) || normalizedTerm.contains(normalizedEntity)) {
                    return 0.7;
                }
            }
        }
        return 0.0;
    }

    private List<ScoredMemory> diversifyByType(List<ScoredMemory> ranked, List<String> typeNames, int limit) {
        if (ranked.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (typeNames == null || typeNames.isEmpty()) {
            return ranked.subList(0, Math.min(limit, ranked.size()));
        }

        Map<String, Queue<ScoredMemory>> grouped = new LinkedHashMap<>();
        for (String typeName : typeNames) {
            grouped.put(typeName, new ArrayDeque<>());
        }
        for (ScoredMemory scored : ranked) {
            grouped.computeIfAbsent(scored.memory.getMemoryType(), key -> new ArrayDeque<>()).add(scored);
        }

        List<ScoredMemory> selected = new ArrayList<>();
        boolean progressed = true;
        while (selected.size() < limit && progressed) {
            progressed = false;
            for (Queue<ScoredMemory> queue : grouped.values()) {
                ScoredMemory next = queue.poll();
                if (next != null) {
                    selected.add(next);
                    progressed = true;
                    if (selected.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return selected;
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

    private LongTermMemory mergeMemory(LongTermMemory existing,
                                       String newContent,
                                       String newImportance,
                                       String sessionId,
                                       Map<String, Object> newMetadata) {
        String mergedContent = existing.getContent() + "\n---\n" + newContent;
        String mergedImportance = mergeImportance(existing.getImportance(), newImportance);
        Map<String, Object> mergedMetadata = metadataSupport.merge(
                existing.getContent(),
                newContent,
                existing.getMemoryType(),
                existing.getScope(),
                existing.getProjectId(),
                sessionId != null ? sessionId : existing.getSessionId(),
                mergedImportance,
                existing.getMetadata(),
                newMetadata
        );
        String retrievalText = metadataSupport.buildRetrievalText(mergedContent, existing.getMemoryType(), mergedMetadata);
        float[] embedding = embeddingService.embed(retrievalText);

        LongTermMemory updated = LongTermMemory.builder()
                .id(existing.getId())
                .content(mergedContent)
                .importance(mergedImportance)
                .metadata(metadataSupport.toJson(mergedMetadata))
                .embedding(embedding)
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
                .metadata(metadataSupport.parseMetadata(memory.getMetadata()))
                .accessCount(memory.getAccessCount())
                .lastAccessedAt(memory.getLastAccessedAt())
                .createdAt(memory.getCreatedAt())
                .updatedAt(memory.getUpdatedAt())
                .similarity(memory.getSimilarity())
                .build();
    }

    private double defaultSearchThreshold() {
        return Math.max(0.0, properties.getRetrieval().getSimilarityThreshold());
    }

    private double importanceBoost(String importance) {
        if ("HIGH".equals(importance)) {
            return 0.10;
        }
        if ("MEDIUM".equals(importance)) {
            return 0.04;
        }
        return 0.0;
    }

    private double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String normalizeSearchText(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static class ScoredMemory {
        private final LongTermMemory memory;
        private boolean textMatched;
        private double score;

        private ScoredMemory(LongTermMemory memory, boolean textMatched) {
            this.memory = memory;
            this.textMatched = textMatched;
        }
    }
}
