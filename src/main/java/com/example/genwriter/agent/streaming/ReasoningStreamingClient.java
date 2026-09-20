package com.example.genwriter.agent.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 推理模型流式客户端
 * 通过原始 SSE 调用 chat/completions 接口，解析 reasoning_content 字段
 * 支持所有兼容该接口的推理模型供应商
 *
 * <p>扩展：支持 OpenAI function calling（tools 请求体 + delta.tool_calls 按 index 分片聚合），
 * 供 {@link ReasoningChatModelDecorator} 在推理采集路径上完成完整工具循环。
 */
@Slf4j
@Component
public class ReasoningStreamingClient {

    private static final String FINISH_TOOL_CALLS = "tool_calls";
    private static final String FINISH_STOP = "stop";

    private final ObjectMapper objectMapper;

    public ReasoningStreamingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record StreamingResult(String reasoningContent, String content) {}

    /**
     * 单轮流式调用的聚合结果：reasoning 全文、content 全文、聚合后的 tool_calls、finish_reason。
     */
    public record RoundResult(String reasoningContent, String content,
                              List<AssistantMessage.ToolCall> toolCalls, String finishReason) {}

    public interface ChunkCallback {
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
    }

    /**
     * 流式调用推理模型 API，支持 reasoning_content 解析
     *
     * @param baseUrl          API 基础地址（不可为空）
     * @param apiKey           API 密钥
     * @param model            模型名称
     * @param messages         消息列表
     * @param temperature      温度参数
     * @param enableThinking   是否启用思考模式（部分供应商特有参数）
     * @param callback         内容回调
     * @return 流式调用结果
     */
    public StreamingResult stream(String baseUrl, String apiKey, String model,
                                  List<Map<String, String>> messages, double temperature,
                                  boolean enableThinking, ChunkCallback callback) {
        List<Map<String, Object>> genericMessages = new ArrayList<>();
        for (Map<String, String> message : messages) {
            genericMessages.add(new LinkedHashMap<>(message));
        }
        RoundResult result = streamRound(baseUrl, apiKey, model, genericMessages,
                temperature, enableThinking, null, callback);
        return new StreamingResult(result.reasoningContent(), result.content());
    }

    /**
     * 单轮流式调用：解析 reasoning_content / content / tool_calls 三类 delta 并聚合。
     * reasoning 与 content chunk 通过 callback 实时回调（供装饰器透传给消费方）。
     *
     * @param tools           OpenAI function 定义（可空；空则不携带 tools 参数）
     * @param callback        chunk 实时回调（可空）
     * @return 聚合结果（含按 index 分片聚合后的 tool_calls 与 finish_reason）
     */
    public RoundResult streamRound(String baseUrl, String apiKey, String model,
                                   List<Map<String, Object>> messages, Double temperature,
                                   boolean enableThinking, List<Map<String, Object>> tools,
                                   ChunkCallback callback) {
        return streamRound(baseUrl, null, apiKey, model, messages, temperature, enableThinking,
                tools, callback);
    }

    /**
     * {@link #streamRound} 的重载：用供应商的 api-compatibility 决定端点是否需要 {@code /v1} 前缀。
     *
     * @param apiCompatibility 供应商的 api-compatibility 配置值（如 openai_compatible / native）
     */
    public RoundResult streamRound(String baseUrl, String apiCompatibility, String apiKey, String model,
                                   List<Map<String, Object>> messages, Double temperature,
                                   boolean enableThinking, List<Map<String, Object>> tools,
                                   ChunkCallback callback) {
        String endpoint = resolveChatCompletionsUrl(baseUrl, apiCompatibility);
        RoundResult result = streamOnce(apiKey, model, messages, temperature, enableThinking,
                tools, callback, endpoint);
        if (result == null) {
            String alternate = withoutVersionSegment(endpoint);
            if (alternate != null) {
                log.warn("chat/completions 首次调用失败，改用备用端点重试: {}", alternate);
                result = streamOnce(apiKey, model, messages, temperature, enableThinking,
                        tools, callback, alternate);
            }
        }
        return result != null ? result : new RoundResult("", "", List.of(), null);
    }

    /**
     * 解析 chat/completions 端点。OpenAI 兼容供应商统一走
     * {@code {base-url}/v1/chat/completions}：base-url 已带版本段（如 {@code .../compatible-mode/v1}）
     * 时按原样拼接，避免出现 {@code /v1/v1}；DashScope 的 {@code .../compatible-mode} 少这一层
     * 会直接 404，DeepSeek 两种写法都通，故只对缺失版本段的 base-url 补 {@code /v1}；
     * native 供应商沿用原有的 {@code {base-url}/chat/completions}。
     */
    static String resolveChatCompletionsUrl(String baseUrl, String apiCompatibility) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("base-url 不能为空，请在 application.yml 中配置供应商的 base-url");
        }
        String base = baseUrl.replaceAll("/+$", "");
        if ("native".equalsIgnoreCase(apiCompatibility) || base.matches(".*/v\\d+$")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    /** 去掉端点里的版本段（{@code /v1/chat/completions} → {@code /chat/completions}）；无版本段时返回 null。 */
    private static String withoutVersionSegment(String endpoint) {
        String alternate = endpoint.replaceFirst("/v\\d+/chat/completions$", "/chat/completions");
        return alternate.equals(endpoint) ? null : alternate;
    }

    private RoundResult streamOnce(String apiKey, String model,
                                    List<Map<String, Object>> messages, Double temperature,
                                    boolean enableThinking, List<Map<String, Object>> tools,
                                    ChunkCallback callback, String endpoint) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (enableThinking) {
            body.put("enable_thinking", true);
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        StringBuilder reasoningBuilder = new StringBuilder();
        StringBuilder contentBuilder = new StringBuilder();
        // index -> 分片聚合（id/name/arguments 可能分散在多个 delta 中）
        TreeMap<Integer, ToolCallAgg> toolCallAgg = new TreeMap<>();
        AtomicReference<String> finishReasonRef = new AtomicReference<>(null);

        try {
            WebClient.builder()
                    .build()
                    .post()
                    .uri(java.net.URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .doOnNext(sse -> {
                        String data = sse.data();
                        if (data != null) {
                            parseLine("data:" + data, reasoningBuilder, contentBuilder,
                                    toolCallAgg, finishReasonRef, callback);
                        }
                    })
                    .doOnError(e -> log.warn("SSE streaming error: {}", e.getMessage()))
                    .blockLast(Duration.ofMinutes(10));
        } catch (Exception e) {
            // 返回 null 交给调用方尝试备用端点；流中途异常时保留已聚合内容，避免丢掉响应
            if (finishReasonRef.get() == null) {
                return null;
            }
            log.warn("SSE streaming failed: {}", e.getMessage());
        }

        return new RoundResult(
                reasoningBuilder.toString(),
                contentBuilder.toString(),
                materializeToolCalls(toolCallAgg),
                finishReasonRef.get());
    }

    /** 是否为工具调用结束轮（finish_reason=tool_calls）。 */
    public static boolean isToolCallRound(RoundResult result) {
        return result != null && FINISH_TOOL_CALLS.equals(result.finishReason())
                && result.toolCalls() != null && !result.toolCalls().isEmpty();
    }

    /** 是否为正常结束轮。 */
    public static boolean isStopRound(RoundResult result) {
        return result != null && (FINISH_STOP.equals(result.finishReason()) || result.finishReason() == null);
    }

    private List<AssistantMessage.ToolCall> materializeToolCalls(TreeMap<Integer, ToolCallAgg> agg) {
        if (agg.isEmpty()) return List.of();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        agg.values().forEach(a -> calls.add(new AssistantMessage.ToolCall(
                a.id, "function", a.name, a.arguments.toString())));
        return calls;
    }

    private void parseLine(String line, StringBuilder reasoningBuilder, StringBuilder contentBuilder,
                           TreeMap<Integer, ToolCallAgg> toolCallAgg,
                           AtomicReference<String> finishReasonRef, ChunkCallback callback) {
        if (line == null) return;
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) return;
        String data = trimmed.substring(5).trim();
        if ("[DONE]".equals(data)) return;
        if (data.isEmpty()) return;

        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            if (delta.isMissingNode()) return;

            // finish_reason（通常出现在最后一个 delta）
            JsonNode finishNode = choice.path("finish_reason");
            if (!finishNode.isMissingNode() && !finishNode.isNull()
                    && !finishNode.asText("").isEmpty()) {
                finishReasonRef.set(finishNode.asText());
            }

            // 提取 reasoning_content（推理模型通用字段）
            JsonNode reasoningNode = delta.get("reasoning_content");
            if (reasoningNode != null && !reasoningNode.isNull()) {
                String reasoning = reasoningNode.asText("");
                if (!reasoning.isEmpty()) {
                    reasoningBuilder.append(reasoning);
                    if (callback != null) callback.onReasoningChunk(reasoning);
                }
            }

            // 提取 content
            JsonNode contentNode = delta.get("content");
            if (contentNode != null && !contentNode.isNull()) {
                String content = contentNode.asText("");
                if (!content.isEmpty()) {
                    contentBuilder.append(content);
                    if (callback != null) callback.onContentChunk(content);
                }
            }

            // 提取 tool_calls 分片（按 index 聚合 arguments）
            JsonNode toolCallsNode = delta.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray()) {
                for (JsonNode tc : toolCallsNode) {
                    int index = tc.path("index").asInt(0);
                    ToolCallAgg agg = toolCallAgg.computeIfAbsent(index, k -> new ToolCallAgg());
                    JsonNode idNode = tc.get("id");
                    if (idNode != null && !idNode.isNull() && !idNode.asText("").isEmpty()) {
                        agg.id = idNode.asText();
                    }
                    JsonNode fn = tc.path("function");
                    if (!fn.isMissingNode()) {
                        JsonNode nameNode = fn.get("name");
                        if (nameNode != null && !nameNode.isNull() && !nameNode.asText("").isEmpty()) {
                            agg.name = nameNode.asText();
                        }
                        JsonNode argsNode = fn.get("arguments");
                        if (argsNode != null && !argsNode.isNull()) {
                            agg.arguments.append(argsNode.asText(""));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE chunk: {}", e.getMessage());
        }
    }

    private static final class ToolCallAgg {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
