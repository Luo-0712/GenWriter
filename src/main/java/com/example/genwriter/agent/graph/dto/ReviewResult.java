package com.example.genwriter.agent.graph.dto;

/**
 * 内容评审结构化输出
 */
public record ReviewResult(
        int score,
        String verdict,
        Dimensions dimensions,
        String feedback
) {
    public record Dimensions(
            int structure,
            int content,
            int language,
            int logic,
            int relevance
    ) {
    }
}
