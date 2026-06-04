package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.IntentRecognitionSkill;
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

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognitionWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.1;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final IntentRecognitionSkill skill;
    private final WorkerRegistry registry;
    private final ThoughtChainPublisher chainPublisher;

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

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput));
        String response;
        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型识别意图",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE), null);
        try {
            response = chatClient.prompt()
                    .system(skill.systemPrompt())
                    .user(userPrompt)
                    .call()
                    .content();
            chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                    Map.of("outputLength", response != null ? response.length() : 0));
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
                            "reason", result.reason()));
            return Map.of("intent", result.intent(), "writingType", result.writingType());
        } catch (Exception e) {
            log.warn("意图解析失败，降级 UNKNOWN: response={}", response, e);
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("intent", "UNKNOWN", "writingType", "CREATE", "fallback", true));
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
