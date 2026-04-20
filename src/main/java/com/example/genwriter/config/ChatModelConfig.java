package com.example.genwriter.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ChatModel 配置类
 * 使用 Spring AI Alibaba 提供的 ChatModel
 * 支持通义千问(DashScope)和OpenAI兼容API
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ChatModelConfig {

    private final LLMConfig llmConfig;

    /**
     * 创建动态 ChatModel
     * 注入 Spring AI Alibaba 提供的 ChatModel Bean
     *
     * @param dashScopeChatModel Spring AI Alibaba 自动配置的 DashScope ChatModel
     * @return DynamicChatModel 实例
     */
    @Bean
    @Primary
    public DynamicChatModel dynamicChatModel(
            @Qualifier("dashScopeChatModel") ChatModel dashScopeChatModel) {
        DynamicChatModel dynamicModel = new DynamicChatModel(llmConfig.getDefaultModel());

        // 注册 DashScope 模型（通义千问）
        dynamicModel.registerModel("dashscope", dashScopeChatModel);
        dynamicModel.registerModel(llmConfig.getDefaultModel(), dashScopeChatModel);

        log.info("已注册 DashScope ChatModel，默认模型: {}", llmConfig.getDefaultModel());

        return dynamicModel;
    }
}
