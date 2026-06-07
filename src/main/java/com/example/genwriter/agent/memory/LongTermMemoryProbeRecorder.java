package com.example.genwriter.agent.memory;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.message.AgentTraceEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LongTermMemoryProbeRecorder {

    private final LongTermMemoryProperties properties;
    private final ThoughtChainPublisher chainPublisher;

    private final ArrayDeque<Map<String, Object>> recentEvents = new ArrayDeque<>();

    public void recordRetrieval(String sessionId,
                                List<String> queries,
                                List<String> memoryTypes,
                                List<Map<String, Object>> queryResults,
                                List<Map<String, Object>> injectedMemories,
                                int injectedCount) {
        record(sessionId, "retrieval", Map.of(
                "queries", queries != null ? queries : List.of(),
                "memoryTypes", memoryTypes != null ? memoryTypes : List.of(),
                "queryResults", queryResults != null ? queryResults : List.of(),
                "injectedMemories", injectedMemories != null ? injectedMemories : List.of(),
                "injectedCount", injectedCount
        ));
    }

    public void recordWriteDecision(String sessionId,
                                    String decision,
                                    String memoryId,
                                    String memoryType,
                                    String identityKey,
                                    String authority,
                                    String updatePolicy,
                                    int memoryVersion) {
        record(sessionId, "writeDecision", Map.of(
                "decision", safe(decision),
                "memoryId", safe(memoryId),
                "memoryType", safe(memoryType),
                "identityKey", safe(identityKey),
                "authority", safe(authority),
                "updatePolicy", safe(updatePolicy),
                "memoryVersion", memoryVersion
        ));
    }

    public List<Map<String, Object>> recentEvents(String sessionId) {
        synchronized (recentEvents) {
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (Map<String, Object> event : recentEvents) {
                if (sessionId == null || sessionId.isBlank() || sessionId.equals(event.get("sessionId"))) {
                    result.add(new LinkedHashMap<>(event));
                }
            }
            return result;
        }
    }

    private void record(String sessionId, String eventType, Map<String, Object> payload) {
        if (!properties.getProbe().isEnabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", safe(sessionId));
        event.put("eventType", eventType);
        event.put("timestamp", Instant.now().toString());
        event.put("payload", payload != null ? payload : Map.of());

        synchronized (recentEvents) {
            recentEvents.addLast(event);
            int limit = Math.max(1, properties.getProbe().getRecentEventLimit());
            while (recentEvents.size() > limit) {
                recentEvents.removeFirst();
            }
        }

        String spanId = chainPublisher.publishTraceStart(sessionId, "长期记忆探针",
                AgentTraceEvent.Kind.MEMORY, null, Map.of("eventType", eventType), null);
        chainPublisher.publishTraceComplete(sessionId, spanId, event,
                Map.of("eventType", eventType));
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
