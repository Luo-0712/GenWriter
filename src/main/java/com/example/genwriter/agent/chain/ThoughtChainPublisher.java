package com.example.genwriter.agent.chain;

import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThoughtChainPublisher {

    private final SseService sseService;

    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();
    private final Map<String, TraceSpan> traceSpans = new ConcurrentHashMap<>();

    /**
     * Per-session 的 trace 事件缓冲，任务结束时由 StateGraphRunner 取走并序列化进 message metadata 落库。
     * 缓冲事件为浅拷贝副本，后续 {@link #publishComplete} 回填 reasoningContent 时会以最新 reasoning 覆盖。
     */
    private final ConcurrentMap<String, List<AgentTraceEvent>> traceEventBuffer = new ConcurrentHashMap<>();

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
                .reasoningContent(reasoningContent)
                .startedAt(startTime)
                .endedAt(now)
                .durationMs(duration)
                .timestamp(now)
                .build());
    }

    /**
     * 推送模型推理内容 chunk（实时），使用独立的 AI_REASONING_CHUNK 事件类型，
     * 与 AI_THINKING 的纯状态文本语义解耦。
     */
    public void publishReasoningChunk(String sessionId, String nodeId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_REASONING_CHUNK)
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
        bufferTraceEvent(sessionId, event);
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

    /**
     * 把 trace 事件追加到 per-session 缓冲，供任务结束时落库恢复。
     * 同 spanId 的事件以"最新覆盖"语义合并：reasoningContent / output / metadata 等字段
     * 后到的事件覆盖先到的，保证 COMPLETED 事件回填的 reasoningContent 能反映到最终缓冲。
     */
    private void bufferTraceEvent(String sessionId, AgentTraceEvent event) {
        if (event == null) return;
        List<AgentTraceEvent> buffer = traceEventBuffer
                .computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        // 同 spanId 事件就地合并：找到既有项则用本事件非空字段覆盖，否则追加
        String spanId = event.getSpanId();
        if (spanId != null) {
            for (int i = buffer.size() - 1; i >= 0; i--) {
                AgentTraceEvent existing = buffer.get(i);
                if (spanId.equals(existing.getSpanId())) {
                    AgentTraceEvent merged = mergeTraceEvent(existing, event);
                    buffer.set(i, merged);
                    return;
                }
            }
        }
        buffer.add(event);
    }

    private AgentTraceEvent mergeTraceEvent(AgentTraceEvent base, AgentTraceEvent incoming) {
        return AgentTraceEvent.builder()
                .traceId(incoming.getTraceId() != null ? incoming.getTraceId() : base.getTraceId())
                .eventId(incoming.getEventId() != null ? incoming.getEventId() : base.getEventId())
                .spanId(incoming.getSpanId() != null ? incoming.getSpanId() : base.getSpanId())
                .parentSpanId(incoming.getParentSpanId() != null ? incoming.getParentSpanId() : base.getParentSpanId())
                .kind(incoming.getKind() != null ? incoming.getKind() : base.getKind())
                .phase(incoming.getPhase() != null ? incoming.getPhase() : base.getPhase())
                .name(incoming.getName() != null ? incoming.getName() : base.getName())
                .agentName(incoming.getAgentName() != null ? incoming.getAgentName() : base.getAgentName())
                .toolName(incoming.getToolName() != null ? incoming.getToolName() : base.getToolName())
                .summary(incoming.getSummary() != null ? incoming.getSummary() : base.getSummary())
                .input(incoming.getInput() != null ? incoming.getInput() : base.getInput())
                .output(incoming.getOutput() != null ? incoming.getOutput() : base.getOutput())
                .metadata(incoming.getMetadata() != null ? incoming.getMetadata() : base.getMetadata())
                .reasoningContent(incoming.getReasoningContent() != null
                        ? incoming.getReasoningContent() : base.getReasoningContent())
                .error(incoming.getError() != null ? incoming.getError() : base.getError())
                .startedAt(incoming.getStartedAt() != null ? incoming.getStartedAt() : base.getStartedAt())
                .endedAt(incoming.getEndedAt() != null ? incoming.getEndedAt() : base.getEndedAt())
                .durationMs(incoming.getDurationMs() != null ? incoming.getDurationMs() : base.getDurationMs())
                .timestamp(incoming.getTimestamp() != null ? incoming.getTimestamp() : base.getTimestamp())
                .build();
    }

    /**
     * 取走本 session 累积的 trace 事件（用于落库到 message metadata）。
     * 返回的是缓冲快照副本，调用方取走后缓冲被清空。
     */
    public List<AgentTraceEvent> drainTraceEvents(String sessionId) {
        if (sessionId == null) return List.of();
        List<AgentTraceEvent> buffer = traceEventBuffer.remove(sessionId);
        return buffer != null ? new ArrayList<>(buffer) : List.of();
    }

    /**
     * 清空本 session 的 trace 缓冲（任务结束、出错或超时时调用，防止内存泄漏）。
     */
    public void clearTraceEvents(String sessionId) {
        if (sessionId == null) return;
        traceEventBuffer.remove(sessionId);
    }

    /**
     * 推送 ReAct 单步决策（thought / reasoning / action）作为 LLM span。
     * 由 SupervisorNode 的 ReAct StepHook 在 BEFORE 阶段调用，让前端 trace 树能看到每步决策依据。
     */
    public String publishReactDecisionTrace(String sessionId, String parentSpanId,
                                            String thought, String reasoning, String action) {
        long now = System.currentTimeMillis();
        String spanId = generateNodeId("react-decision");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", action != null ? action : "");
        if (reasoning != null && !reasoning.isBlank()) metadata.put("reasoning", reasoning);
        if (thought != null && !thought.isBlank()) metadata.put("thought", thought);
        AgentTraceEvent event = AgentTraceEvent.builder()
                .traceId(sessionId)
                .eventId(generateEventId())
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(AgentTraceEvent.Kind.LLM.name())
                .phase(AgentTraceEvent.Phase.STARTED.name())
                .name("ReAct 决策")
                .summary(thought != null && !thought.isBlank() ? truncate(thought, TEXT_SUMMARY_LIMIT) : "ReAct 决策")
                .metadata(summarizeMap(metadata))
                .startedAt(now)
                .timestamp(now)
                .build();
        traceSpans.put(spanId, new TraceSpan(sessionId, parentSpanId, "ReAct 决策", AgentTraceEvent.Kind.LLM, now));
        publishTraceEvent(sessionId, event);
        return spanId;
    }

    /**
     * 标记 ReAct 决策 span 完成。
     */
    public void publishReactDecisionComplete(String sessionId, String spanId) {
        publishTraceComplete(sessionId, spanId, null, null);
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
