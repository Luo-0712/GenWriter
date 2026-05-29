package com.example.genwriter.agent.streaming;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.config.ChatModelFactory;
import com.example.genwriter.config.DynamicChatModel;
import com.example.genwriter.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 统一流式调用助手
 * 对推理模型使用原始 HTTP SSE 以采集 reasoning_content
 * 对普通模型使用标准 Spring AI 流式 API
 */
@Slf4j
@Component
public class ReasoningStreamHelper {

    private final DynamicChatModel dynamicChatModel;
    private final ChatModelFactory chatModelFactory;
    private final DeepSeekStreamingClient deepSeekClient;
    private final ThoughtChainPublisher chainPublisher;

    public ReasoningStreamHelper(DynamicChatModel dynamicChatModel,
                                 ChatModelFactory chatModelFactory,
                                 DeepSeekStreamingClient deepSeekClient,
                                 ThoughtChainPublisher chainPublisher) {
        this.dynamicChatModel = dynamicChatModel;
        this.chatModelFactory = chatModelFactory;
        this.deepSeekClient = deepSeekClient;
        this.chainPublisher = chainPublisher;
    }

    /**
     * 流式调用结果
     */
    public record StreamResult(String content, String reasoningContent) {}

    /**
     * 统一内容 chunk 回调
     */
    public interface ContentChunkCallback {
        void onContentChunk(String chunk);
    }

    /**
     * 流式调用，自动判断是否使用推理路径
     *
     * @param sessionId     会话 ID（用于 SSE 推送）
     * @param nodeId        链节点 ID（用于关联 reasoningContent）
     * @param systemPrompt  系统提示词
     * @param userPrompt    用户提示词
     * @param temperature   温度参数
     * @param contentCallback 内容 chunk 回调
     * @return 流式调用结果（含 content 和 reasoningContent）
     */
    public StreamResult stream(String sessionId, String nodeId,
                               String systemPrompt, String userPrompt,
                               double temperature,
                               ContentChunkCallback contentCallback) {
        if (isReasoningModel()) {
            return streamViaDeepSeek(sessionId, nodeId, systemPrompt, userPrompt,
                    temperature, contentCallback);
        }
        // 非推理模型不走此路径，由调用方使用标准 ChatClient 流式
        throw new UnsupportedOperationException(
                "非推理模型请直接使用 ChatClient.prompt().stream().content()");
    }

    /**
     * 判断当前激活模型是否为推理模型
     */
    public boolean isReasoningModel() {
        String activeModelKey = dynamicChatModel.getActiveModel();
        LLMConfig.ProviderConfig config = chatModelFactory.findProviderConfig(activeModelKey);
        return chatModelFactory.isReasoningModel(config);
    }

    /**
     * 获取当前推理模型的 ProviderConfig（仅推理模型有效）
     */
    public LLMConfig.ProviderConfig getReasoningProviderConfig() {
        String activeModelKey = dynamicChatModel.getActiveModel();
        return chatModelFactory.findProviderConfig(activeModelKey);
    }

    private StreamResult streamViaDeepSeek(String sessionId, String nodeId,
                                     String systemPrompt, String userPrompt,
                                     double temperature,
                                     ContentChunkCallback contentCallback) {
        LLMConfig.ProviderConfig config = getReasoningProviderConfig();
        if (config == null) {
            throw new IllegalStateException("无法获取推理模型配置");
        }

        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        );

        DeepSeekStreamingClient.StreamingResult result = deepSeekClient.stream(
                config.getBaseUrl(), config.getApiKey(), config.getActiveModel(),
                messages, temperature, true,
                new DeepSeekStreamingClient.ChunkCallback() {
                    @Override
                    public void onReasoningChunk(String chunk) {
                        chainPublisher.publishReasoningChunk(sessionId, nodeId, chunk);
                    }

                    @Override
                    public void onContentChunk(String chunk) {
                        contentCallback.onContentChunk(chunk);
                    }
                }
        );

        return new StreamResult(result.content(), result.reasoningContent());
    }
}
