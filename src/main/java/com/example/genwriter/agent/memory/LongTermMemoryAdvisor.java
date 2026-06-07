package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
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

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, null, null);
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries) {
        this(memoryService, promptFormatter, memoryTypes, sessionId, preExtractedQueries, null);
    }

    public LongTermMemoryAdvisor(LongTermMemoryService memoryService,
                                 LongTermMemoryPromptFormatter promptFormatter,
                                 List<MemoryType> memoryTypes,
                                 String sessionId,
                                 List<String> preExtractedQueries,
                                 LongTermMemoryProbeRecorder probeRecorder) {
        this.memoryService = memoryService;
        this.promptFormatter = promptFormatter;
        this.memoryTypes = memoryTypes;
        this.sessionId = sessionId;
        this.preExtractedQueries = preExtractedQueries;
        this.probeRecorder = probeRecorder;
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

            String memoryContext = promptFormatter.format(new ArrayList<>(merged.values()));
            if (memoryContext.isBlank()) {
                publishProbe(queries, queryResults, probeItems(new ArrayList<>(merged.values())), 0);
                return chain.nextAroundCall(request);
            }
            publishProbe(queries, queryResults, probeItems(new ArrayList<>(merged.values())), merged.size());
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
