package com.example.genwriter.agent.streaming;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.config.DynamicChatModel;
import com.example.genwriter.config.LLMConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 推理模型 ChatModel 装饰器：把 reasoning_content 采集下沉到标准管线。
 *
 * <p>包装 {@link com.example.genwriter.config.DynamicChatModel}：
 * <ul>
 *   <li>当前激活模型为推理模型（yml 中 provider 配置 reasoning: true）时，
 *       通过原始 HTTP SSE 解析 reasoning_content / content / tool_calls 三类 delta，
 *       reasoning 与 function calling 共存，消除旧「raw SSE 旁路不支持工具」的互斥限制；</li>
 *   <li>非推理模型完全透明，直接委托原 ChatModel；</li>
 *   <li>reasoning delta 经 {@link ThoughtChainPublisher#publishReasoningChunk} 以
 *       AI_REASONING_CHUNK 实时推送（span 取自 {@link SessionContextHolder}），
 *       前端「模型思考过程」区块流式渲染；</li>
 *   <li>工具调用循环在本装饰器内部完成（执行 ToolCallback → append tool 消息 → 重发请求），
 *       工具回调按 ToolContext 恢复会话上下文并自行发布 TOOL trace span，行为与原管线一致。</li>
 * </ul>
 *
 * <p>本装饰器动态判定（每次调用时检查当前激活模型），支持运行时切换模型：
 * 切到非推理模型后自动回退 delegate 原生路径。
 */
@Slf4j
@Component
public class ReasoningChatModelDecorator implements ChatModel {

    /** 防御性工具循环上限：超过则返回已积累内容，避免无限循环。 */
    private static final int MAX_TOOL_ROUNDS = 8;

    private final DynamicChatModel delegate;
    private final ReasoningStreamHelper reasoningStreamHelper;
    private final ReasoningStreamingClient streamingClient;
    private final ThoughtChainPublisher chainPublisher;

    public ReasoningChatModelDecorator(DynamicChatModel delegate,
                                       ReasoningStreamHelper reasoningStreamHelper,
                                       ReasoningStreamingClient streamingClient,
                                       ThoughtChainPublisher chainPublisher) {
        this.delegate = delegate;
        this.reasoningStreamHelper = reasoningStreamHelper;
        this.streamingClient = streamingClient;
        this.chainPublisher = chainPublisher;
    }

    /** {@link #callWithReasoning(Prompt)} 的返回：content 正文 + reasoning 全文。 */
    public record ReasoningCallResult(String content, String reasoningContent) {}

    @Override
    public ChatResponse call(Prompt prompt) {
        if (!reasoningStreamHelper.isReasoningModel()) {
            return delegate.call(prompt);
        }
        ReasoningCallResult result = callWithReasoning(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(result.content()))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (!reasoningStreamHelper.isReasoningModel()) {
            return delegate.stream(prompt);
        }
        return Flux.create(sink -> {
            try {
                runStreamingToolLoop(prompt, sink);
                sink.complete();
            } catch (Exception e) {
                log.error("[ReasoningChatModelDecorator] streaming tool loop failed: {}", e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    /**
     * 聚合调用（不推 AI_REASONING_CHUNK），返回正文与 reasoning 全文。
     * 供 SupervisorNode ReAct 决策等需要「思考全文」的场景使用；内部同样支持工具循环。
     */
    public ReasoningCallResult callWithReasoning(Prompt prompt) {
        CallSession session = prepareSession(prompt);
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        StringBuilder reasoningAll = new StringBuilder();
        StringBuilder contentAll = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ReasoningStreamingClient.RoundResult result = streamingClient.streamRound(
                    session.baseUrl(), session.apiCompatibility(), session.apiKey(), session.model(),
                    toOpenAiMessages(messages), session.temperature(), session.enableThinking(),
                    session.tools(), null);
            reasoningAll.append(result.reasoningContent());
            contentAll.append(result.content());

            if (!ReasoningStreamingClient.isToolCallRound(result)) {
                break;
            }
            appendToolRound(messages, result, session.toolCallbacks(), session.toolContext());
            if (round == MAX_TOOL_ROUNDS - 1) {
                log.warn("[ReasoningChatModelDecorator] tool loop reached max rounds ({})", MAX_TOOL_ROUNDS);
            }
        }
        return new ReasoningCallResult(contentAll.toString(), reasoningAll.toString());
    }

    // ==================== 内部实现 ====================

    private void runStreamingToolLoop(Prompt prompt, FluxSink<ChatResponse> sink) {
        StreamSession session = prepareStreamSession(prompt);
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        StringBuilder reasoningAll = new StringBuilder();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ReasoningStreamingClient.RoundResult result = streamingClient.streamRound(
                    session.baseUrl(), session.apiCompatibility(), session.apiKey(), session.model(),
                    toOpenAiMessages(messages), session.temperature(), session.enableThinking(),
                    session.tools(),
                    new ReasoningStreamingClient.ChunkCallback() {
                        @Override
                        public void onReasoningChunk(String chunk) {
                            reasoningAll.append(chunk);
                            if (hasText(session.sessionId()) && hasText(session.spanId())) {
                                chainPublisher.publishReasoningChunk(session.sessionId(), session.spanId(), chunk);
                            }
                        }

                        @Override
                        public void onContentChunk(String chunk) {
                            sink.next(contentChunk(chunk));
                        }
                    });

            if (!ReasoningStreamingClient.isToolCallRound(result)) {
                break;
            }
            // 工具轮：assistant(tool_calls) + tool 结果 append 后重发请求
            appendToolRound(messages, result, session.toolCallbacks(), session.toolContext());
            log.debug("[ReasoningChatModelDecorator] tool round {} executed", round + 1);
        }

        // 思考全文补录进 trace 缓冲（AI_REASONING_CHUNK 不落库，保证刷新后可恢复）
        if (hasText(session.sessionId()) && hasText(session.spanId()) && reasoningAll.length() > 0) {
            chainPublisher.publishReasoningComplete(session.sessionId(), session.spanId(),
                    reasoningAll.toString());
        }
    }

    private void appendToolRound(List<Message> messages, ReasoningStreamingClient.RoundResult result,
                                 List<FunctionCallback> toolCallbacks, ToolContext toolContext) {
        messages.add(new AssistantMessage(
                result.content() != null ? result.content() : "", Map.of(), result.toolCalls()));
        List<ToolResponseMessage.ToolResponse> responses =
                executeTools(result.toolCalls(), toolCallbacks, toolContext);
        messages.add(new ToolResponseMessage(responses));
    }

    private record StreamSession(String baseUrl, String apiCompatibility, String apiKey, String model,
                                 Double temperature, boolean enableThinking,
                                 List<Map<String, Object>> tools,
                                 List<FunctionCallback> toolCallbacks, ToolContext toolContext,
                                 String sessionId, String spanId) {}

    private record CallSession(String baseUrl, String apiCompatibility, String apiKey, String model,
                               Double temperature, boolean enableThinking,
                               List<Map<String, Object>> tools,
                               List<FunctionCallback> toolCallbacks, ToolContext toolContext) {}

    private StreamSession prepareStreamSession(Prompt prompt) {
        CallSession callSession = prepareSession(prompt);
        SessionContextHolder.ContextSnapshot snapshot = SessionContextHolder.snapshot();
        return new StreamSession(callSession.baseUrl(), callSession.apiCompatibility(),
                callSession.apiKey(), callSession.model(),
                callSession.temperature(), callSession.enableThinking(), callSession.tools(),
                callSession.toolCallbacks(), callSession.toolContext(),
                snapshot != null ? snapshot.sessionId() : null,
                snapshot != null ? snapshot.currentSpanId() : null);
    }

    private CallSession prepareSession(Prompt prompt) {
        LLMConfig.ProviderConfig config = reasoningStreamHelper.getReasoningProviderConfig();
        if (config == null) {
            throw new IllegalStateException("无法获取推理模型供应商配置");
        }
        ChatOptions options = prompt.getOptions();
        List<FunctionCallback> toolCallbacks = extractToolCallbacks(options);
        Map<String, Object> toolContextMap = extractToolContext(options);
        ToolContext toolContext = new ToolContext(toolContextMap != null ? toolContextMap : Map.of());
        return new CallSession(
                config.getBaseUrl(),
                config.getApiCompatibility(),
                config.getApiKey(),
                config.getActiveModel(),
                options != null ? options.getTemperature() : null,
                reasoningStreamHelper.resolveEnableThinking(config),
                toolCallbacks.isEmpty() ? null : buildToolDefinitions(toolCallbacks),
                toolCallbacks,
                toolContext);
    }

    /** 把 Spring AI 消息序列转成 OpenAI 兼容请求体消息数组（含 media 的消息仅保留文本部分）。 */
    private List<Map<String, Object>> toOpenAiMessages(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message message : messages) {
            switch (message.getMessageType()) {
                case SYSTEM -> result.add(Map.of("role", "system", "content", message.getText()));
                case USER -> result.add(Map.of("role", "user", "content", message.getText()));
                case ASSISTANT -> {
                    if (message instanceof AssistantMessage assistant) {
                        result.add(toAssistantEntry(assistant));
                    } else {
                        result.add(Map.of("role", "assistant", "content", message.getText()));
                    }
                }
                case TOOL -> {
                    if (message instanceof ToolResponseMessage toolMessage) {
                        for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("role", "tool");
                            entry.put("tool_call_id", response.id());
                            entry.put("content", response.responseData() != null ? response.responseData() : "");
                            result.add(entry);
                        }
                    }
                }
                default -> log.debug("[ReasoningChatModelDecorator] skip unsupported message type: {}",
                        message.getMessageType());
            }
        }
        return result;
    }

    private Map<String, Object> toAssistantEntry(AssistantMessage assistant) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("role", "assistant");
        entry.put("content", assistant.getText() != null ? assistant.getText() : "");
        if (assistant.hasToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", call.arguments() != null ? call.arguments() : "{}");
                Map<String, Object> callEntry = new LinkedHashMap<>();
                callEntry.put("id", call.id());
                callEntry.put("type", "function");
                callEntry.put("function", function);
                calls.add(callEntry);
            }
            entry.put("tool_calls", calls);
        }
        return entry;
    }

    private List<Map<String, Object>> buildToolDefinitions(List<FunctionCallback> callbacks) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (FunctionCallback callback : callbacks) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", callback.getName());
            function.put("description", callback.getDescription());
            function.put("parameters", callback.getInputTypeSchema());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            tools.add(tool);
        }
        return tools;
    }

    /** 兼容新旧两代 options 接口，提取运行时注册的工具回调。 */
    @SuppressWarnings("deprecation")
    private List<FunctionCallback> extractToolCallbacks(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions toolCallingOptions
                && toolCallingOptions.getToolCallbacks() != null) {
            return toolCallingOptions.getToolCallbacks();
        }
        if (options instanceof FunctionCallingOptions functionOptions
                && functionOptions.getFunctionCallbacks() != null) {
            return functionOptions.getFunctionCallbacks();
        }
        return List.of();
    }

    private Map<String, Object> extractToolContext(ChatOptions options) {
        if (options instanceof FunctionCallingOptions functionOptions) {
            return functionOptions.getToolContext();
        }
        return null;
    }

    private List<ToolResponseMessage.ToolResponse> executeTools(List<AssistantMessage.ToolCall> toolCalls,
                                                                List<FunctionCallback> callbacks,
                                                                ToolContext toolContext) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : toolCalls) {
            String toolName = call.name() != null ? call.name() : "";
            String arguments = call.arguments() != null ? call.arguments() : "{}";
            try {
                FunctionCallback matched = findTool(callbacks, toolName);
                String result = matched != null
                        ? matched.call(arguments, toolContext)
                        : "Unknown tool: " + toolName;
                if (matched == null) {
                    log.warn("[ReasoningChatModelDecorator] model called unknown tool: {}", toolName);
                }
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), toolName, result));
            } catch (Exception e) {
                log.error("[ReasoningChatModelDecorator] tool execution failed: tool={}, error={}",
                        toolName, e.getMessage(), e);
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), toolName,
                        "Tool execution failed: " + e.getMessage()));
            }
        }
        return responses;
    }

    private FunctionCallback findTool(List<FunctionCallback> callbacks, String name) {
        if (callbacks == null || name == null) return null;
        for (FunctionCallback callback : callbacks) {
            if (name.equals(callback.getName())) {
                return callback;
            }
        }
        return null;
    }

    private ChatResponse contentChunk(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
