package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.IntentRecognitionSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
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
        return "分析用户输入，判断意图和写作类型（WRITING_TASK/KNOWLEDGE_QA/POLISH_TASK/GENERAL_QA）";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String userInput = (String) state.getOrDefault("userInput", "");

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput));
        String response = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .call()
                .content();

        try {
            String json = stripMarkdownCodeBlock(response);
            IntentResult result = objectMapper.readValue(json, IntentResult.class);
            log.info("意图识别: intent={}, writingType={}", result.intent(), result.writingType());
            return Map.of("intent", result.intent(), "writingType", result.writingType());
        } catch (Exception e) {
            log.warn("意图解析失败，降级 UNKNOWN: response={}", response, e);
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

    public record IntentResult(String intent, String writingType, String reason) {}
}
