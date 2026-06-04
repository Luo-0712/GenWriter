package com.example.genwriter.agent.chain;

import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThoughtChainPublisher {

    private final SseService sseService;

    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private final Map<String, TraceSpan> traceSpans = new ConcurrentHashMap<>();

    private static final int TEXT_SUMMARY_LIMIT = 240;

    public String publishStart(String sessionId, String nodeName, ChainNode.Type nodeType,
                               String parentId, Object input) {
        String nodeId = generateNodeId(nodeName);
        long now = System.currentTimeMillis();
        String effectiveParentId = parentId != null ? parentId
                : com.example.genwriter.agent.tool.SessionContextHolder.getCurrentSpanId();
        startTimes.put(nodeId, now);
        traceSpans.put(nodeId, new TraceSpan(sessionId, effectiveParentId, nodeName, resolveKind(nodeType), now));

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .nodeName(nodeName)
                .nodeType(nodeType.name())
                .parentId(effectiveParentId)
                .status(ChainNode.Status.STARTED.name())
                .input(input)
                .timestamp(now)
                .build();

        publishChainNode(sessionId, chainNode);
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(nodeId)
                .parentSpanId(effectiveParentId)
                .kind(resolveKind(nodeType).name())
                .phase(AgentTraceEvent.Phase.STARTED.name())
                .name(nodeName)
                .summary(nodeName + "开始")
                .input(summarizeObject(input))
                .startedAt(now)
                .timestamp(now)
                .build());
        return nodeId;
    }

    public void publishRunning(String sessionId, String nodeId, String statusText) {
        TraceSpan span = traceSpans.get(nodeId);
        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.RUNNING.name())
                .output(statusText)
                .timestamp(System.currentTimeMillis())
                .build();

        publishChainNode(sessionId, chainNode);
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(nodeId)
                .parentSpanId(span != null ? span.parentSpanId() : null)
                .kind(span != null ? span.kind().name() : AgentTraceEvent.Kind.STATUS.name())
                .phase(AgentTraceEvent.Phase.RUNNING.name())
                .name(span != null ? span.name() : null)
                .summary(statusText)
                .output(summarizeObject(statusText))
                .timestamp(System.currentTimeMillis())
                .build());
    }

    public void publishComplete(String sessionId, String nodeId, Object output) {
        publishComplete(sessionId, nodeId, output, null);
    }

    public void publishComplete(String sessionId, String nodeId, Object output, String reasoningContent) {
        Long startTime = startTimes.remove(nodeId);
        TraceSpan span = traceSpans.remove(nodeId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        long now = System.currentTimeMillis();

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.COMPLETED.name())
                .output(output)
                .reasoningContent(reasoningContent)
                .duration(duration)
                .timestamp(now)
                .build();

        publishChainNode(sessionId, chainNode);
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(nodeId)
                .parentSpanId(span != null ? span.parentSpanId() : null)
                .kind(span != null ? span.kind().name() : AgentTraceEvent.Kind.STATUS.name())
                .phase(AgentTraceEvent.Phase.COMPLETED.name())
                .name(span != null ? span.name() : null)
                .summary(buildSummary(output))
                .output(summarizeObject(output))
                .startedAt(startTime)
                .endedAt(now)
                .durationMs(duration)
                .timestamp(now)
                .build());
    }

    /**
     * 推送模型推理内容 chunk（实时）
     */
    public void publishReasoningChunk(String sessionId, String nodeId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .data(Map.of("nodeId", nodeId, "reasoningChunk", chunk))
                            .statusText("模型思考中...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Reasoning chunk publish failed: {}", e.getMessage());
        }
    }

    public void publishError(String sessionId, String nodeId, String error) {
        Long startTime = startTimes.remove(nodeId);
        TraceSpan span = traceSpans.remove(nodeId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;
        long now = System.currentTimeMillis();

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.ERROR.name())
                .error(error)
                .duration(duration)
                .timestamp(now)
                .build();

        publishChainNode(sessionId, chainNode);
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(nodeId)
                .parentSpanId(span != null ? span.parentSpanId() : null)
                .kind(AgentTraceEvent.Kind.ERROR.name())
                .phase(AgentTraceEvent.Phase.ERROR.name())
                .name(span != null ? span.name() : null)
                .summary("执行失败")
                .error(truncate(error, TEXT_SUMMARY_LIMIT))
                .startedAt(startTime)
                .endedAt(now)
                .durationMs(duration)
                .timestamp(now)
                .build());
    }

    public void publishDirect(String sessionId, String nodeId, String nodeName,
                              ChainNode.Type nodeType, String parentId,
                              ChainNode.Status status, Object input, Object output,
                              Long duration) {
        long now = System.currentTimeMillis();
        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .nodeName(nodeName)
                .nodeType(nodeType.name())
                .parentId(parentId)
                .status(status.name())
                .input(input)
                .output(output)
                .duration(duration)
                .timestamp(now)
                .build();

        publishChainNode(sessionId, chainNode);
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(nodeId)
                .parentSpanId(parentId)
                .kind(resolveKind(nodeType).name())
                .phase(resolvePhase(status).name())
                .name(nodeName)
                .summary(buildSummary(output))
                .input(summarizeObject(input))
                .output(summarizeObject(output))
                .durationMs(duration)
                .timestamp(now)
                .build());
    }

    public String publishTraceStart(String sessionId, String name, AgentTraceEvent.Kind kind,
                                    String parentSpanId, Object input, Map<String, Object> metadata) {
        String spanId = generateNodeId(name);
        long now = System.currentTimeMillis();
        traceSpans.put(spanId, new TraceSpan(sessionId, parentSpanId, name, kind, now));
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(kind.name())
                .phase(AgentTraceEvent.Phase.STARTED.name())
                .name(name)
                .summary(name + "开始")
                .input(summarizeObject(input))
                .metadata(summarizeMap(metadata))
                .startedAt(now)
                .timestamp(now)
                .build());
        return spanId;
    }

    public void publishTraceComplete(String sessionId, String spanId, Object output) {
        publishTraceComplete(sessionId, spanId, output, null);
    }

    public void publishTraceComplete(String sessionId, String spanId, Object output, Map<String, Object> metadata) {
        TraceSpan span = traceSpans.remove(spanId);
        long now = System.currentTimeMillis();
        Long startedAt = span != null ? span.startedAt() : null;
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(spanId)
                .parentSpanId(span != null ? span.parentSpanId() : null)
                .kind(span != null ? span.kind().name() : AgentTraceEvent.Kind.STATUS.name())
                .phase(AgentTraceEvent.Phase.COMPLETED.name())
                .name(span != null ? span.name() : null)
                .summary(buildSummary(output))
                .output(summarizeObject(output))
                .metadata(summarizeMap(metadata))
                .startedAt(startedAt)
                .endedAt(now)
                .durationMs(startedAt != null ? now - startedAt : null)
                .timestamp(now)
                .build());
    }

    public void publishTraceError(String sessionId, String spanId, String error) {
        TraceSpan span = traceSpans.remove(spanId);
        long now = System.currentTimeMillis();
        Long startedAt = span != null ? span.startedAt() : null;
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(spanId)
                .parentSpanId(span != null ? span.parentSpanId() : null)
                .kind(AgentTraceEvent.Kind.ERROR.name())
                .phase(AgentTraceEvent.Phase.ERROR.name())
                .name(span != null ? span.name() : null)
                .summary("执行失败")
                .error(truncate(error, TEXT_SUMMARY_LIMIT))
                .startedAt(startedAt)
                .endedAt(now)
                .durationMs(startedAt != null ? now - startedAt : null)
                .timestamp(now)
                .build());
    }

    public String publishToolStart(String sessionId, String toolName, Object input) {
        return publishToolStart(sessionId, toolName, input, null);
    }

    public String publishToolStart(String sessionId, String toolName, Object input, String parentSpanId) {
        String safeToolName = toolName != null ? toolName : "tool";
        String actualParentId = parentSpanId != null ? parentSpanId
                : com.example.genwriter.agent.tool.SessionContextHolder.getCurrentSpanId();
        String spanId = publishTraceStart(sessionId, toolDisplayName(safeToolName),
                AgentTraceEvent.Kind.TOOL, actualParentId, input,
                Map.of("toolName", safeToolName));
        return spanId;
    }

    public void publishToolComplete(String sessionId, String spanId, String toolName, Object output) {
        publishTraceComplete(sessionId, spanId, output, Map.of("toolName", toolName != null ? toolName : "tool"));
    }

    public void publishToolError(String sessionId, String spanId, String toolName, String error) {
        publishTraceError(sessionId, spanId, error);
    }

    public void publishStatusTrace(String sessionId, String parentSpanId, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        publishTraceEvent(sessionId, AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(generateNodeId("status"))
                .parentSpanId(parentSpanId)
                .kind(AgentTraceEvent.Kind.STATUS.name())
                .phase(AgentTraceEvent.Phase.RUNNING.name())
                .name("状态更新")
                .summary(statusText)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private void publishChainNode(String sessionId, ChainNode chainNode) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_CHAIN_EVENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chainNode)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Chain node publish failed: {}", e.getMessage());
        }
    }

    private void publishTraceEvent(String sessionId, AgentTraceEvent event) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_TRACE_EVENT)
                    .payload(SseMessage.Payload.builder()
                            .data(event)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Trace event publish failed: {}", e.getMessage());
        }
    }

    private String generateNodeId(String nodeName) {
        return nodeName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String generateEventId() {
        return "evt-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private AgentTraceEvent.Kind resolveKind(ChainNode.Type nodeType) {
        if (nodeType == null) return AgentTraceEvent.Kind.STATUS;
        return switch (nodeType) {
            case PLANNING -> AgentTraceEvent.Kind.SUPERVISOR;
            case TOOL_CALL -> AgentTraceEvent.Kind.TOOL;
            case THINKING, EXECUTION, RESULT -> AgentTraceEvent.Kind.WORKER;
            case ERROR -> AgentTraceEvent.Kind.ERROR;
        };
    }

    private AgentTraceEvent.Phase resolvePhase(ChainNode.Status status) {
        if (status == null) return AgentTraceEvent.Phase.RUNNING;
        return switch (status) {
            case STARTED -> AgentTraceEvent.Phase.STARTED;
            case RUNNING -> AgentTraceEvent.Phase.RUNNING;
            case COMPLETED -> AgentTraceEvent.Phase.COMPLETED;
            case ERROR -> AgentTraceEvent.Phase.ERROR;
        };
    }

    private String buildSummary(Object output) {
        if (output == null) return "已完成";
        if (output instanceof String s) return truncate(s, TEXT_SUMMARY_LIMIT);
        if (output instanceof Map<?, ?> map) {
            if (map.containsKey("summary")) return truncate(String.valueOf(map.get("summary")), TEXT_SUMMARY_LIMIT);
            if (map.containsKey("error")) return truncate(String.valueOf(map.get("error")), TEXT_SUMMARY_LIMIT);
            if (map.containsKey("worker")) return "完成 " + map.get("worker");
            if (map.containsKey("length")) return "生成 " + map.get("length") + " 字符";
            if (map.containsKey("searchRounds")) return "完成 " + map.get("searchRounds") + " 次检索";
            if (map.containsKey("total")) return "返回 " + map.get("total") + " 条结果";
            if (map.containsKey("intent")) return "识别为 " + map.get("intent");
        }
        return "已完成";
    }

    private Object summarizeObject(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return truncate(s, TEXT_SUMMARY_LIMIT);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> summarized = new LinkedHashMap<>();
            map.forEach((key, val) -> summarized.put(String.valueOf(key), summarizeObject(val)));
            return summarized;
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) count++;
            return Map.of("count", count);
        }
        return value;
    }

    private Map<String, Object> summarizeMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        Map<String, Object> summarized = new LinkedHashMap<>();
        map.forEach((key, value) -> summarized.put(key, summarizeObject(value)));
        return summarized;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLen ? normalized : normalized.substring(0, maxLen) + "...";
    }

    private String toolDisplayName(String toolName) {
        if (toolName == null || toolName.isBlank()) return "工具调用";
        return switch (toolName) {
            case "web_search" -> "联网搜索";
            case "knowledge_base_search" -> "知识库检索";
            case "update_writing_skill" -> "保存写作技巧";
            case "save_setting_detail" -> "保存设定细节";
            case "read_skill_detail" -> "读取 Skill 详情";
            default -> toolName;
        };
    }

    private record TraceSpan(String sessionId, String parentSpanId, String name,
                             AgentTraceEvent.Kind kind, long startedAt) {}
}
