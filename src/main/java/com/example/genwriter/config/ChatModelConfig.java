package com.example.genwriter.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;

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
    private final ChatModelFactory chatModelFactory;

    /**
     * 自定义 RestClient.Builder，设置 15 分钟读取超时
     * 覆盖 Spring Boot 默认配置，供 Spring AI Alibaba 使用
     */
    @Bean
    @Scope("prototype")
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout((int) llmConfig.getTimeout().toMillis());
        log.info("RestClient 超时配置: connectTimeout=30s, readTimeout={}s",
                llmConfig.getTimeout().getSeconds());
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * 创建动态 ChatModel
     * 注入 Spring AI Alibaba 提供的 ChatModel Bean，并从 YAML 加载所有已配置供应商
     *
     * @param dashScopeChatModel Spring AI Alibaba 自动配置的 dashscope ChatModel
     * @return DynamicChatModel 实例
     */
    @Bean
    @Primary
    public DynamicChatModel dynamicChatModel(
            @Qualifier("dashscopeChatModel") ChatModel dashScopeChatModel) {
        DynamicChatModel dynamicModel = new DynamicChatModel(llmConfig.getDefaultModel());

        // 始终注册自动配置的 DashScope 作为兜底
        dynamicModel.registerModel("dashscope", dashScopeChatModel);
        dynamicModel.registerModel(llmConfig.getDefaultModel(), dashScopeChatModel);

        // 从 YAML providers 列表加载所有供应商
        List<LLMConfig.ProviderConfig> providers = llmConfig.getProviders();
        String defaultKey = llmConfig.getDefaultProvider() + ":" + llmConfig.getDefaultModel();

        for (LLMConfig.ProviderConfig provider : providers) {
            try {
                ChatModel model = chatModelFactory.createChatModel(provider);
                for (String modelName : provider.getModels()) {
                    String key = provider.getType() + ":" + modelName;
                    dynamicModel.registerModel(key, model);
                    log.info("注册模型: {} ({})", key, provider.getDisplayName());
                }
                // 注册简称键（如 "dashscope"、"openai"）方便切换
                String providerKey = provider.getType() + ":" + provider.getActiveModel();
                if (provider.getType().equals(llmConfig.getDefaultProvider())) {
                    dynamicModel.switchModel(providerKey);
                }
            } catch (Exception e) {
                log.warn("注册供应商失败 [{}]: {}", provider.getDisplayName(), e.getMessage());
            }
        }

        log.info("当前激活模型: {}", dynamicModel.getActiveModel());
        return dynamicModel;
    }

    /**
     * 创建默认 ChatClient，供 Graph 节点直接注入使用
     * 应用 LLMConfig 中的 temperature 和 maxTokens 默认配置
     */
    @Bean
    public ChatClient chatClient(DynamicChatModel dynamicChatModel) {
        return ChatClient.builder(dynamicChatModel)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withMaxToken(llmConfig.getDefaultMaxTokens())
                        .withTemperature(llmConfig.getDefaultTemperature())
                        .build())
                .build();
    }
}
