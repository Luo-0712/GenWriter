package com.example.genwriter.agent.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 端点拼接回归：DashScope 兼容模式必须带 /v1，DeepSeek 自带版本段时不得重复补。
 */
class ReasoningStreamingClientUrlTest {

    @Test
    void openAiCompatibleBareBaseUrlGetsV1Segment() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl(
                        "https://dashscope.aliyuncs.com/compatible-mode", "openai_compatible"));
    }

    @Test
    void deepSeekBaseUrlAlreadyCarriesVersionSegment() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl(
                        "https://api.deepseek.com/v1", "openai_compatible"));
    }

    @Test
    void bareBaseUrlGetsV1Segment() {
        // DeepSeek 的 /chat/completions 与 /v1/chat/completions 均可访问，统一补 /v1 以保证兼容
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl(
                        "https://api.deepseek.com", "openai_compatible"));
    }

    @Test
    void trailingSlashIsTrimmed() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl(
                        "https://dashscope.aliyuncs.com/compatible-mode/", "openai_compatible"));
    }

    @Test
    void nativeCompatibilityKeepsLegacyPath() {
        assertEquals("https://example.com/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl("https://example.com", "native"));
    }

    @Test
    void unsetCompatibilityAlsoGetsV1Segment() {
        // 旧调用点未传 api-compatibility 时也走 OpenAPI 兼容默认值，保证 DashScope 能命中
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                ReasoningStreamingClient.resolveChatCompletionsUrl(
                        "https://dashscope.aliyuncs.com/compatible-mode", null));
    }

    @Test
    void blankBaseUrlIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> ReasoningStreamingClient.resolveChatCompletionsUrl("  ", "openai_compatible"));
    }
}
