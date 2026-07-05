package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.profile.AgentPromptRenderer;
import com.example.genwriter.agent.profile.RenderedAgentPrompt;
import com.example.genwriter.agent.skill.NovelWritingPromptSupport;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognitionWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.1;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final AgentPromptRenderer agentPromptRenderer;
    private final WorkerRegistry registry;
    private final ThoughtChainPublisher chainPublisher;
    private final ReasoningStreamHelper reasoningStreamHelper;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "intent_recognition";
    }

    @Override
    public String description() {
        return "分析用户输入，判断意图和写作类型（WRITING_TASK/KNOWLEDGE_QA/POLISH_TASK/RESEARCH_TASK/GENERAL_QA）";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");

        String nodeId = chainPublisher.publishStart(sessionId, "意图识别",
                ChainNode.Type.THINKING, null,
                Map.of("userInput", truncate(userInput, 200)));

        String forcedWritingType = NovelWritingPromptSupport.forcedWritingType(userInput);
        if (forcedWritingType != null) {
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("intent", "WRITING_TASK", "writingType", forcedWritingType,
                            "reason", "novel_creation_signal", "forced", true));
            return Map.of("intent", "WRITING_TASK", "writingType", forcedWritingType);
        }

        RenderedAgentPrompt renderedPrompt = agentPromptRenderer.render(name(), Map.of("userInput", userInput));
        String systemPrompt = renderedPrompt.systemPrompt();
        String userPrompt = renderedPrompt.userPrompt();
        StringBuilder contentBuilder = new StringBuilder();
        String response;
        String reasoningContent = null;
        SessionContextHolder.set(sessionId, nodeId, name());
        boolean reasoningCaptured = reasoningStreamHelper.isReasoningModel();
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型识别意图",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE,
                        "reasoningCaptured", reasoningCaptured), null);
        try {
            if (reasoningCaptured) {
                var result = reasoningStreamHelper.stream(sessionId, nodeId,
                        systemPrompt, userPrompt, TEMPERATURE,
                        contentBuilder::append);
                response = result.content();
                reasoningContent = result.reasoningContent();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", response != null ? response.length() : 0,
                                "reasoningLength", reasoningContent != null ? reasoningContent.length() : 0,
                                "reasoningCaptured", true));
            } else {
                chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(contentBuilder::append)
                        .then(Mono.just(contentBuilder.toString()))
                        .block();
                response = contentBuilder.toString();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", response != null ? response.length() : 0,
                                "reasoningCaptured", false));
            }
        } catch (Exception e) {
            chainPublisher.publishTraceError(sessionId, llmSpanId, e.getMessage());
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        try {
            String json = stripMarkdownCodeBlock(response);
            IntentResult result = objectMapper.readValue(json, IntentResult.class);
            log.info("意图识别: intent={}, writingType={}", result.intent(), result.writingType());
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("intent", result.intent(), "writingType", result.writingType(),
                            "reason", result.reason()), reasoningContent);
            return Map.of("intent", result.intent(), "writingType", result.writingType());
        } catch (Exception e) {
            log.warn("意图解析失败，降级 UNKNOWN: response={}", response, e);
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("intent", "UNKNOWN", "writingType", "CREATE", "fallback", true),
                    reasoningContent);
            return Map.of("intent", "UNKNOWN", "writingType", "CREATE");
        }
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    public record IntentResult(String intent, String writingType, String reason) {}
}
