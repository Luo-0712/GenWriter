package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
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
public class DirectAnswerWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final DirectAnswerSkill skill;
    private final WorkerRegistry registry;
    private final SseService sseService;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "direct_answer";
    }

    @Override
    public String description() {
        return "直接回答用户问题，用于简单问答或作为最终输出生成器";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String context = (String) state.getOrDefault("context", "");

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput, "context", context));

        StringBuilder contentBuilder = new StringBuilder();
        chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    contentBuilder.append(chunk);
                    publishContentChunk(sessionId, chunk);
                })
                .then(Mono.just(contentBuilder.toString()))
                .block();

        String fullResponse = contentBuilder.toString();
        log.info("直接回答完成: length={}", fullResponse.length());
        return Map.of("finalOutput", fullResponse);
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("【直接回答】生成中...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE content chunk failed: {}", e.getMessage());
        }
    }
}
