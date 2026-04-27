package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.PolishSkill;
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
 * 润色节点
 * 对文章进行润色优化，使用流式输出实时反馈给前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolishNode implements NodeAction {
    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final PolishSkill skill;
    private final SseService sseService;

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String draft = state.value("draft", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String reviewFeedback = state.value("reviewFeedback", String.class).orElse("");

        // 如果是直接润色任务（POLISH），userInput 是要润色的内容
        String contentToPolish = draft.isBlank() ? userInput : draft;

        log.debug("润色: contentLength={}, hasFeedback={}", contentToPolish.length(), !reviewFeedback.isBlank());
        publishStatus(sessionId, "【润色】正在优化文章表达...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "content", contentToPolish,
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
        log.debug("润色完成: polishedLength={}", fullResponse.length());
        publishStatus(sessionId, "【润色】完成，长度=" + fullResponse.length() + " 字符");

        return Map.of(
                "polishedContent", fullResponse,
                "finalOutput", fullResponse,
                "currentNode", "PolishNode"
        );
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("【润色】优化中...")
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
