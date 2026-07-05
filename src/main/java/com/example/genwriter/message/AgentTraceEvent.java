package com.example.genwriter.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 实时执行轨迹事件。
 * 通过 SSE 推送 + 内存频道缓存，并在任务结束时由 ThoughtChainPublisher 缓冲后
 * 序列化进 message metadata 落库，刷新页面可恢复。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentTraceEvent {

    private String traceId;
    private String eventId;
    private String spanId;
    private String parentSpanId;
    private String kind;
    private String phase;
    private String name;
    private String agentName;
    private String toolName;
    private String summary;
    private Object input;
    private Object output;
    private Map<String, Object> metadata;
    /**
     * 推理模型的完整思维链内容（reasoning_content），LLM span 完成时回填。
     * 用于 trace 落库后刷新恢复展示。
     */
    private String reasoningContent;
    private String error;
    private Long startedAt;
    private Long endedAt;
    private Long durationMs;
    private Long timestamp;

    public enum Kind {
        SUPERVISOR,
        WORKER,
        LLM,
        TOOL,
        RAG,
        MEMORY,
        STATUS,
        ERROR
    }

    public enum Phase {
        STARTED,
        RUNNING,
        COMPLETED,
        ERROR
    }
}
