package com.example.genwriter.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * 动态 ChatModel 工厂
 * 根据供应商配置创建对应的 ChatModel 实例
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /**
     * 根据供应商配置创建 ChatModel
     */
    public ChatModel createChatModel(LLMConfig.ProviderConfig config) {
        String type = config.getType();
        String compatibility = config.getApiCompatibility();

        if ("openai_compatible".equals(compatibility) || "deepseek".equals(type)) {
            return createOpenAiCompatible(config);
        }

        return switch (type) {
            case "dashscope" -> createDashScope(config);
            case "openai" -> createOpenAi(config);
            case "anthropic" -> createAnthropic(config);
            default -> throw new IllegalArgumentException("不支持的供应商类型: " + type);
        };
    }

    private ChatModel createDashScope(LLMConfig.ProviderConfig config) {
        DashScopeApi api = new DashScopeApi(config.getApiKey());
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(config.getActiveModel())
                .build();
        return new DashScopeChatModel(api, options);
    }

    private ChatModel createOpenAi(LLMConfig.ProviderConfig config) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(config.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getActiveModel())
                .build();
        return new OpenAiChatModel(api, options);
    }

    private ChatModel createOpenAiCompatible(LLMConfig.ProviderConfig config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(config.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getActiveModel())
                .build();
        return new OpenAiChatModel(api, options);
    }

    private ChatModel createAnthropic(LLMConfig.ProviderConfig config) {
        AnthropicApi api = new AnthropicApi(config.getApiKey());
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(config.getActiveModel())
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
    }
}
