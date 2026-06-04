package com.example.genwriter.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 实时执行轨迹事件。
 * 当前仅通过 SSE 推送和内存频道缓存，不落库；字段设计保留后续持久化空间。
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
