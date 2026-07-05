package com.example.genwriter.agent.streaming;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.config.ChatModelFactory;
import com.example.genwriter.config.DynamicChatModel;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReasoningStreamHelperTest {

    @Mock
    private DynamicChatModel dynamicChatModel;
    @Mock
    private ChatModelFactory chatModelFactory;
    @Mock
    private ReasoningStreamingClient streamingClient;
    @Mock
    private ThoughtChainPublisher chainPublisher;
    @Mock
    private SseService sseService;

    private ReasoningStreamHelper helper;

    @BeforeEach
    void setUp() {
        helper = new ReasoningStreamHelper(dynamicChatModel, chatModelFactory, streamingClient, chainPublisher);
    }

    private LLMConfig.ProviderConfig provider(String type, boolean reasoning, Boolean thinkingParam) {
        LLMConfig.ProviderConfig config = new LLMConfig.ProviderConfig();
        config.setType(type);
        config.setReasoning(reasoning);
        config.setThinkingParam(thinkingParam);
        config.setApiKey("k");
        config.setBaseUrl("https://example.test");
        config.setActiveModel("model-x");
        return config;
    }

    private void stubActiveModel(String modelKey, LLMConfig.ProviderConfig config) {
        when(dynamicChatModel.getActiveModel()).thenReturn(modelKey);
        when(chatModelFactory.findProviderConfig(modelKey)).thenReturn(config);
        when(chatModelFactory.isReasoningModel(config)).thenReturn(config.isReasoning());
    }

    @Test
    void isReasoningModel_trueWhenProviderReasoningFlagSet() {
        LLMConfig.ProviderConfig config = provider("deepseek", true, null);
        stubActiveModel("deepseek:deepseek-reasoner", config);

        assertTrue(helper.isReasoningModel());
    }

    @Test
    void isReasoningModel_falseForNonReasoningProvider() {
        LLMConfig.ProviderConfig config = provider("dashscope", false, null);
        stubActiveModel("dashscope:qwen3.7-max", config);

        assertFalse(helper.isReasoningModel());
    }

    @Test
    void stream_routesToRawSseAndPublishesReasoningChunk() {
        LLMConfig.ProviderConfig config = provider("deepseek", true, null);
        stubActiveModel("deepseek:deepseek-reasoner", config);

        when(streamingClient.stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), ArgumentMatchers.anyBoolean(),
                any(ReasoningStreamingClient.ChunkCallback.class)))
                .thenAnswer(invocation -> {
                    ReasoningStreamingClient.ChunkCallback cb = invocation.getArgument(6);
                    cb.onReasoningChunk("思考片段1");
                    cb.onContentChunk("正文片段");
                    return new ReasoningStreamingClient.StreamingResult("思考片段1", "正文片段");
                });

        ReasoningStreamHelper.StreamResult result = helper.stream(
                "session-1", "node-1", "sys", "user", 0.5, chunk -> {});

        assertEquals("正文片段", result.content());
        assertEquals("思考片段1", result.reasoningContent());
        verify(chainPublisher).publishReasoningChunk(eq("session-1"), eq("node-1"), eq("思考片段1"));
    }

    @Test
    void enableThinking_inferredTrueForDeepseekReasoningModel() {
        LLMConfig.ProviderConfig config = provider("deepseek", true, null);
        stubActiveModel("deepseek:deepseek-reasoner", config);
        when(streamingClient.stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), ArgumentMatchers.anyBoolean(),
                any(ReasoningStreamingClient.ChunkCallback.class)))
                .thenReturn(new ReasoningStreamingClient.StreamingResult("", ""));

        helper.stream("s", "n", "sys", "u", 0.5, c -> {});

        ArgumentCaptor<Boolean> enableCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(streamingClient).stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), enableCaptor.capture(),
                any(ReasoningStreamingClient.ChunkCallback.class));
        assertTrue(enableCaptor.getValue(), "deepseek 推理模型应推断 enable_thinking=true");
    }

    @Test
    void enableThinking_inferredTrueForDashscopeReasoningModel() {
        // dashscope 下的推理模型（如 qwen3-reasoning / qwq）也应触发 enable_thinking
        LLMConfig.ProviderConfig config = provider("dashscope", true, null);
        stubActiveModel("dashscope:qwen3-reasoning", config);
        when(streamingClient.stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), ArgumentMatchers.anyBoolean(),
                any(ReasoningStreamingClient.ChunkCallback.class)))
                .thenReturn(new ReasoningStreamingClient.StreamingResult("", ""));

        helper.stream("s", "n", "sys", "u", 0.5, c -> {});

        ArgumentCaptor<Boolean> enableCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(streamingClient).stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), enableCaptor.capture(),
                any(ReasoningStreamingClient.ChunkCallback.class));
        assertTrue(enableCaptor.getValue(), "dashscope 推理模型应推断 enable_thinking=true");
    }

    @Test
    void enableThinking_thinkingParamOverrideTakesPrecedence() {
        // 显式 thinkingParam=false 覆盖按 type 的推断
        LLMConfig.ProviderConfig config = provider("deepseek", true, false);
        stubActiveModel("deepseek:deepseek-reasoner", config);
        when(streamingClient.stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), ArgumentMatchers.anyBoolean(),
                any(ReasoningStreamingClient.ChunkCallback.class)))
                .thenReturn(new ReasoningStreamingClient.StreamingResult("", ""));

        helper.stream("s", "n", "sys", "u", 0.5, c -> {});

        ArgumentCaptor<Boolean> enableCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(streamingClient).stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), enableCaptor.capture(),
                any(ReasoningStreamingClient.ChunkCallback.class));
        assertFalse(enableCaptor.getValue(), "显式 thinkingParam=false 应覆盖 type 推断");
    }

    @Test
    void stream_throwsForNonReasoningModel() {
        // 非推理模型不应走 raw SSE 路径，由调用方使用标准 ChatClient 流式
        LLMConfig.ProviderConfig config = provider("dashscope", false, null);
        stubActiveModel("dashscope:qwen3.7-max", config);

        assertThrows(UnsupportedOperationException.class,
                () -> helper.stream("s", "n", "sys", "u", 0.5, c -> {}));
        verify(streamingClient, never()).stream(anyString(), anyString(), anyString(),
                ArgumentMatchers.<List<Map<String, String>>>any(), anyDouble(), ArgumentMatchers.anyBoolean(),
                any(ReasoningStreamingClient.ChunkCallback.class));
    }

    @Test
    void publishReasoningChunk_usesAiReasoningChunkEventType() {
        // 验证 ThoughtChainPublisher.publishReasoningChunk 推送的是 AI_REASONING_CHUNK（不是 AI_THINKING）
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);
        publisher.publishReasoningChunk("session-1", "node-1", "chunk-A");

        ArgumentCaptor<SseMessage> captor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService).publish(eq("session-1"), captor.capture());
        SseMessage msg = captor.getValue();
        assertEquals(SseMessage.Type.AI_REASONING_CHUNK, msg.getType());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) msg.getPayload().getData();
        assertEquals("node-1", data.get("nodeId"));
        assertEquals("chunk-A", data.get("reasoningChunk"));
    }
}
