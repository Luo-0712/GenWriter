package com.example.genwriter.agent.tool;

/**
 * 知识库检索 Tool 的输入参数
 * 供 Spring AI function calling 序列化/反序列化使用
 */
public record KnowledgeSearchInput(
        String query,
        String kbId,
        Integer topK
) {
    public KnowledgeSearchInput {
        if (topK == null || topK < 1) {
            topK = 5;
        }
    }
}
