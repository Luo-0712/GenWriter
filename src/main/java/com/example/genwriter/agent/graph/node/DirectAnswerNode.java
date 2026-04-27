package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 通用问答节点
 * 直接调用 LLM 回答用户问题，使用流式输出实时反馈给前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectAnswerNode implements NodeAction {
    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final DirectAnswerSkill skill;
    private final SseService sseService;

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");

        log.debug("通用问答: userInput={}, contextLength={}", userInput, context.length());
        publishStatus(sessionId, "【直接回答】正在生成回答...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", context
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
        log.debug("通用问答完成: responseLength={}", fullResponse.length());
        publishStatus(sessionId, "【直接回答】完成，长度=" + fullResponse.length() + " 字符");

        return Map.of(
                "finalOutput", fullResponse,
                "currentNode", "DirectAnswerNode"
        );
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("【直接回答】生成中...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 内容推送失败: {}", e.getMessage());
        }
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_EXECUTING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }
}
