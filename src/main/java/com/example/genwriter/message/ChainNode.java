package com.example.genwriter.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChainNode {

    private String nodeId;
    private String nodeName;
    private String nodeType;
    private String parentId;
    private String status;
    private Object input;
    private Object output;
    private Long duration;
    private Long timestamp;
    private Integer stepIndex;
    private String error;
    private String reasoningContent;

    public enum Type {
        PLANNING,
        THINKING,
        TOOL_CALL,
        EXECUTION,
        RESULT,
        ERROR
    }

    public enum Status {
        STARTED,
        RUNNING,
        COMPLETED,
        ERROR
    }
}
