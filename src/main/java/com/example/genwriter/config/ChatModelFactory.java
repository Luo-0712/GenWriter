package com.example.genwriter.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 动态 ChatModel 工厂
 * 根据供应商配置创建对应的 ChatModel 实例
 */
@Slf4j
@Component
public class ChatModelFactory {

    private final LLMConfig llmConfig;
    private final FunctionCallbackResolver functionCallbackResolver;

    /**
     * 使用 @Lazy 延迟加载 FunctionCallback 列表，打破与 ChatModelConfig 的循环依赖：
     * ChatModelConfig → ChatModelFactory → List<FunctionCallback> → KnowledgeBaseTool
     * → RAGPipelineService → KnowledgeChunkService → EmbeddingService
     * → DashScopeEmbeddingModel → RestClient.Builder → ChatModelConfig
     */
    @Autowired
    @Lazy
    private List<FunctionCallback> functionCallbacks;

    public ChatModelFactory(LLMConfig llmConfig, FunctionCallbackResolver functionCallbackResolver) {
        this.llmConfig = llmConfig;
        this.functionCallbackResolver = functionCallbackResolver;
    }

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

    /**
     * 判断给定配置是否为推理模型（支持 reasoning_content）
     */
    public boolean isReasoningModel(LLMConfig.ProviderConfig config) {
        return config != null && config.isReasoning();
    }

    /**
     * 根据模型名称查找 ProviderConfig
     * @param modelKey 格式: "type:activeModel" 或直接 provider type
     */
    public LLMConfig.ProviderConfig findProviderConfig(String modelKey) {
        if (modelKey == null || !modelKey.contains(":")) return null;
        String providerType = modelKey.split(":", 2)[0];
        String modelName = modelKey.split(":", 2)[1];
        return llmConfig.getProviders().stream()
                .filter(p -> p.getType().equals(providerType))
                .filter(p -> p.getModels().contains(modelName))
                .findFirst()
                .orElse(null);
    }

    private ChatModel createDashScope(LLMConfig.ProviderConfig config) {
        DashScopeApi api = new DashScopeApi(config.getApiKey());
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .withModel(config.getActiveModel())
                .build();
        return new DashScopeChatModel(api, options, functionCallbackResolver, functionCallbacks,
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    private ChatModel createOpenAi(LLMConfig.ProviderConfig config) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(config.getApiKey())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(config.getActiveModel())
                .build();
        return new OpenAiChatModel(api, options, functionCallbackResolver, functionCallbacks,
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
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
        return new OpenAiChatModel(api, options, functionCallbackResolver, functionCallbacks,
                RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    private ChatModel createAnthropic(LLMConfig.ProviderConfig config) {
        AnthropicApi api = new AnthropicApi(config.getApiKey());
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(config.getActiveModel())
                .build();
        return new AnthropicChatModel(api, options, RetryUtils.DEFAULT_RETRY_TEMPLATE,
                functionCallbackResolver, functionCallbacks);
    }
}
