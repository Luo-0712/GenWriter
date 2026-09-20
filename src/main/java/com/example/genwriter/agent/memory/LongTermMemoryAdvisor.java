package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.retrieval.GlobalRerankConverger;
import com.example.genwriter.retrieval.gate.GateDecision;
import com.example.genwriter.retrieval.rerank.RerankCandidate;
import com.example.genwriter.retrieval.rerank.RerankResult;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class LongTermMemoryAdvisor implements CallAroundAdvisor {

    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter promptFormatter;
    private final List<MemoryType> memoryTypes;
    private final String sessionId;
    private final List<String> preExtractedQueries;
    private final LongTermMemoryProbeRecorder probeRecorder;
    private final GlobalRerankConverger globalConverger;
    private final LongTermMemoryProperties properties;

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, null, null, null, new LongTermMemoryProperties());
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, preExtractedQueries, null, null,
                new LongTermMemoryProperties());
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries,
                                 LongTermMemoryProbeRecorder probeRecorder) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, preExtractedQueries, probeRecorder, null,
                new LongTermMemoryProperties());
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries,
                                 LongTermMemoryProbeRecorder probeRecorder,
                                 GlobalRerankConverger globalConverger,
                                 LongTermMemoryProperties properties) {
        this.memoryService = memoryService;
        this.promptFormatter = promptFormatter;
        this.memoryTypes = memoryTypes;
        this.sessionId = sessionId;
        this.preExtractedQueries = preExtractedQueries;
        this.probeRecorder = probeRecorder;
        this.globalConverger = globalConverger;
        this.properties = properties != null ? properties : new LongTermMemoryProperties();
    }

    @NotNull
    @Override
    public String getName() {
        return "LongTermMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        List<String> queries = resolveQueries(request);
        if (queries.isEmpty()) {
            return chain.nextAroundCall(request);
        }

        try {
            Map<String, MemoryVO> merged = new LinkedHashMap<>();
            List<Map<String, Object>> queryResults = new ArrayList<>();
            for (String q : queries) {
                if (q == null || q.isBlank()) continue;
                try {
                    List<MemoryVO> results = memoryService.retrieveMemories(q, memoryTypes, sessionId);
                    queryResults.add(Map.of(
                            "query", truncate(q, 160),
                            "resultCount", results.size(),
                            "memories", probeItems(results)
                    ));
                    for (MemoryVO m : results) {
                        merged.putIfAbsent(m.getId(), m);
                    }
                } catch (Exception e) {
                    log.debug("单个query检索失败，继续下一个: query={}, error={}", q, e.getMessage());
                    queryResults.add(Map.of(
                            "query", truncate(q, 160),
                            "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                    ));
                }
            }

            if (merged.isEmpty()) {
                publishProbe(queries, queryResults, List.of(), 0);
                return chain.nextAroundCall(request);
            }

            // 多 query 全局收敛：全局重排 + 门控 + 全局 topN 截断，消除 N×topK 注入放大
            List<MemoryVO> converged = converge(queries, merged);
            if (converged == null) {
                publishProbe(queries, queryResults, List.of(), 0);
                return chain.nextAroundCall(request);
            }
            if (converged.isEmpty()) {
                publishProbe(queries, queryResults, List.of(), 0);
                return chain.nextAroundCall(request);
            }

            String memoryContext = promptFormatter.format(converged);
            if (memoryContext.isBlank()) {
                publishProbe(queries, queryResults, probeItems(converged), 0);
                return chain.nextAroundCall(request);
            }
            publishProbe(queries, queryResults, probeItems(converged), converged.size());
            String baseSystemText = request.systemText() != null ? request.systemText() : "";
            String enhancedSystemText = baseSystemText.isBlank()
                    ? memoryContext
                    : baseSystemText + "\n\n" + memoryContext;
            AdvisedRequest enhancedRequest = AdvisedRequest.from(request)
                    .systemText(enhancedSystemText)
                    .build();

            return chain.nextAroundCall(enhancedRequest);
        } catch (Exception e) {
            log.warn("长期记忆检索失败，跳过注入: {}", e.getMessage());
            return chain.nextAroundCall(request);
        }
    }

    /**
     * 多 query 全局收敛。
     *
     * <p>rerank 可用：跨 query 全局精排（每候选取跨 query 最高分）+ 统一门控 + 全局 topN 截断；
     * rerank 不可用/失败：按相似度降序 + 全局 topN 截断（同样消除注入量放大）。
     *
     * @return 收敛后的记忆列表；返回 null 表示门控拒绝（整段不注入）
     */
    private List<MemoryVO> converge(List<String> queries, Map<String, MemoryVO> merged) {
        int globalTopN = Math.max(1, properties.getRetrieval().getGlobalTopN());
        List<MemoryVO> values = new ArrayList<>(merged.values());

        if (globalConverger == null || !globalConverger.isRerankAvailable()) {
            values.sort(Comparator.comparingDouble(
                    (MemoryVO m) -> m.getSimilarity() != null ? m.getSimilarity() : 0.0).reversed());
            return values.size() > globalTopN ? new ArrayList<>(values.subList(0, globalTopN)) : values;
        }

        try {
            List<RerankCandidate> candidates = values.stream()
                    .map(m -> new RerankCandidate(m.getId(), m.getContent()))
                    .toList();
            List<RerankResult> reranked = globalConverger.globalRerank(queries, candidates, globalTopN);
            if (reranked.isEmpty()) {
                values.sort(Comparator.comparingDouble(
                        (MemoryVO m) -> m.getSimilarity() != null ? m.getSimilarity() : 0.0).reversed());
                return values.size() > globalTopN ? new ArrayList<>(values.subList(0, globalTopN)) : values;
            }

            GateDecision decision = globalConverger.gate(reranked.stream().map(RerankResult::score).toList());
            recordGateDecision(decision, reranked.size());
            if (!decision.pass()) {
                return null;
            }

            List<MemoryVO> ordered = new ArrayList<>();
            for (RerankResult result : reranked) {
                MemoryVO memory = merged.get(result.id());
                if (memory != null) {
                    memory.setSimilarity(result.score());
                    ordered.add(memory);
                }
            }
            return ordered;
        } catch (Exception e) {
            log.debug("全局精排失败，降级为相似度排序: {}", e.getMessage());
            values.sort(Comparator.comparingDouble(
                    (MemoryVO m) -> m.getSimilarity() != null ? m.getSimilarity() : 0.0).reversed());
            return values.size() > globalTopN ? new ArrayList<>(values.subList(0, globalTopN)) : values;
        }
    }

    private void recordGateDecision(GateDecision decision, int candidateCount) {
        if (probeRecorder == null) {
            return;
        }
        try {
            probeRecorder.recordGateDecision(sessionId, "advisor", decision.pass(),
                    decision.topScore(), decision.margin(), decision.rejectReason(), candidateCount);
        } catch (Exception e) {
            log.debug("记录门控探针失败: {}", e.getMessage());
        }
    }

    private List<String> resolveQueries(AdvisedRequest request) {
        if (preExtractedQueries != null && !preExtractedQueries.isEmpty()) {
            return preExtractedQueries;
        }
        String userText = extractUserText(request);
        if (userText != null && !userText.isBlank()) {
            return List.of(userText);
        }
        return List.of();
    }

    private String extractUserText(AdvisedRequest request) {
        String userText = request.userText();
        if (userText != null && !userText.isBlank()) {
            return userText;
        }

        List<Message> messages = request.messages();
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg instanceof UserMessage) {
                    String text = msg.getText();
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private void publishProbe(List<String> queries,
                              List<Map<String, Object>> queryResults,
                              List<Map<String, Object>> injectedMemories,
                              int injectedCount) {
        if (probeRecorder == null) {
            return;
        }
        probeRecorder.recordRetrieval(
                sessionId,
                queries.stream().map(query -> truncate(query, 160)).toList(),
                memoryTypes != null ? memoryTypes.stream().map(MemoryType::name).toList() : List.of(),
                queryResults,
                injectedMemories,
                injectedCount
        );
    }

    private List<Map<String, Object>> probeItems(List<MemoryVO> memories) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (MemoryVO memory : memories) {
            Map<String, Object> metadata = memory.getMetadata() instanceof Map<?, ?> map
                    ? toStringMap(map)
                    : Map.of();
            items.add(Map.of(
                    "id", memory.getId() != null ? memory.getId() : "",
                    "memoryType", memory.getMemoryType() != null ? memory.getMemoryType() : "",
                    "title", stringValue(metadata.get("title")),
                    "identityKey", stringValue(metadata.get("identityKey")),
                    "importance", memory.getImportance() != null ? memory.getImportance() : "",
                    "similarity", memory.getSimilarity() != null ? memory.getSimilarity() : 0.0
            ));
        }
        return items;
    }

    private Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLen ? normalized : normalized.substring(0, maxLen) + "...";
    }

}
