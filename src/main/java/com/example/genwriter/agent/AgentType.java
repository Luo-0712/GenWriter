package com.example.genwriter.agent;

/**
 * Agent 类型枚举
 */
public enum AgentType {

    WRITING("写作助手"),
    OUTLINE("大纲生成"),
    POLISH("文本润色"),
    KNOWLEDGE("知识库问答");

    private final String description;

    AgentType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
