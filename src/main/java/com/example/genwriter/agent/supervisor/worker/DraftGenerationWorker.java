package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.DraftSkill;
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
public class DraftGenerationWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final DraftSkill skill;
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
        return "draft";
    }

    @Override
    public String description() {
        return "根据大纲撰写文章正文，支持评审反馈后重写";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String outline = (String) state.getOrDefault("outline", "");
        String context = (String) state.getOrDefault("context", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String reviewFeedback = (String) state.getOrDefault("reviewFeedback", "");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "outline", outline,
                "context", context,
                "userInput", userInput,
                "reviewFeedback", reviewFeedback
        ));

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
        log.info("正文写作完成: length={}", fullResponse.length());
        return Map.of("draft", fullResponse);
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("正在生成正文...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE content chunk failed: {}", e.getMessage());
        }
    }
}
