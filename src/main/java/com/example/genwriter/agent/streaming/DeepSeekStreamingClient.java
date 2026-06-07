package com.example.genwriter.agent.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek 原始 SSE 流式客户端
 * 绕过 Spring AI，直接解析 reasoning_content 字段
 */
@Slf4j
@Component
public class DeepSeekStreamingClient {

    private final ObjectMapper objectMapper;

    public DeepSeekStreamingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record StreamingResult(String reasoningContent, String content) {}

    public interface ChunkCallback {
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
    }

    /**
     * 流式调用 DeepSeek API，支持 reasoning_content 解析
     */
    public StreamingResult stream(String baseUrl, String apiKey, String model,
                                  List<Map<String, String>> messages, double temperature,
                                  boolean enableThinking, ChunkCallback callback) {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        body.put("temperature", temperature);
        if (enableThinking) {
            body.put("enable_thinking", true);
        }

        AtomicReference<StringBuilder> reasoningBuilder = new AtomicReference<>(new StringBuilder());
        AtomicReference<StringBuilder> contentBuilder = new AtomicReference<>(new StringBuilder());

        String finalBaseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        String uri = finalBaseUrl + "chat/completions";

        try {
            WebClient.builder()
                    .baseUrl(finalBaseUrl)
                    .build()
                    .post()
                    .uri("chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .doOnNext(sse -> {
                        String data = sse.data();
                        if (data != null) {
                            parseLine("data:" + data, reasoningBuilder, contentBuilder, callback);
                        }
                    })
                    .doOnError(e -> log.warn("DeepSeek streaming error: {}", e.getMessage()))
                    .blockLast(Duration.ofMinutes(10));
        } catch (Exception e) {
            log.warn("DeepSeek streaming failed: {}", e.getMessage());
        }

        return new StreamingResult(reasoningBuilder.get().toString(), contentBuilder.get().toString());
    }

    private void parseLine(String line, AtomicReference<StringBuilder> reasoningBuilder,
                           AtomicReference<StringBuilder> contentBuilder, ChunkCallback callback) {
        if (line == null) return;
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) return;
        String data = trimmed.substring(5).trim();
        if ("[DONE]".equals(data)) return;
        if (data.isEmpty()) return;

        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode delta = root.path("choices").path(0).path("delta");
            if (delta.isMissingNode()) return;

            // 提取 reasoning_content
            JsonNode reasoningNode = delta.get("reasoning_content");
            if (reasoningNode != null && !reasoningNode.isNull()) {
                String reasoning = reasoningNode.asText("");
                if (!reasoning.isEmpty()) {
                    reasoningBuilder.get().append(reasoning);
                    callback.onReasoningChunk(reasoning);
                }
            }

            // 提取 content
            JsonNode contentNode = delta.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                String content = contentNode.asText("");
                if (!content.isEmpty()) {
                    contentBuilder.get().append(content);
                    callback.onContentChunk(content);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE chunk: {}", e.getMessage());
        }
    }
}
