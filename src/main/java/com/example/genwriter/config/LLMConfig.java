package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM调用统摄配置
 * 集中管理所有大语言模型相关的配置参数
 * 使用Spring AI Alibaba，提供商配置由spring.ai.alibaba处理
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.llm")
public class LLMConfig {

    /**
     * 默认使用的模型提供商 (dashscope/openai)
     */
    private String defaultProvider = "dashscope";

    /**
     * 默认模型名称
     */
    private String defaultModel = "qwen-plus";

    /**
     * 请求超时时间
     */
    private Duration timeout = Duration.ofSeconds(60);

    /**
     * 最大重试次数
     */
    private Integer maxRetries = 3;

    /**
     * 默认温度参数 (0.0 - 2.0)
     */
    private Double defaultTemperature = 0.7;

    /**
     * 默认最大生成token数
     */
    private Integer defaultMaxTokens = 4096;

    /**
     * 提示词模板配置
     */
    private PromptTemplates prompts = new PromptTemplates();

    /**
     * 提示词模板配置
     */
    @Data
    public static class PromptTemplates {

        /**
         * 写作助手系统提示词
         */
        private String writingSystemPrompt = """
            你是一位专业的写作助手，擅长根据用户的需求提供高质量的写作建议和内容生成。
            请根据用户的输入，提供有帮助、准确且富有创意的回复。
            """;

        /**
         * 文档续写提示词模板
         */
        private String documentContinuationPrompt = """
            请根据以下文档内容继续写作，保持原有风格和语气：

            已有内容：
            {content}

            请继续：
            """;

        /**
         * 文档润色提示词模板
         */
        private String documentPolishPrompt = """
            请对以下文档进行润色优化，提升表达质量，保持原意不变：

            原文：
            {content}

            润色后：
            """;

        /**
         * 知识库问答提示词模板
         */
        private String knowledgeQaPrompt = """
            基于以下知识库内容回答问题：

            知识库内容：
            {context}

            用户问题：{question}

            请根据知识库内容提供准确、简洁的回答。如果知识库中没有相关信息，请明确说明。
            """;
    }
}
