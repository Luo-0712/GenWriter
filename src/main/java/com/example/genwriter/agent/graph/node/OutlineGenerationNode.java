package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.OutlineSkill;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大纲生成节点
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineGenerationNode implements NodeAction {
    private static final double TEMPERATURE = 0.3;

    private final ChatClientFactory chatClientFactory;
    private final OutlineSkill skill;
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

        log.debug("大纲生成: userInput={}", userInput);
        publishStatus(sessionId, "【大纲生成】正在设计文章结构...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", context
        ));
        String response = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .call()
                .content();

        log.debug("大纲生成完成: length={}", response.length());
        publishStatusWithData(sessionId, "【大纲生成】完成，大纲长度=" + response.length() + " 字符", response);

        return Map.of(
                "outline", response,
                "currentNode", "OutlineGenerationNode"
        );
    }

    private void publishStatus(String sessionId, String statusText) {
        publishStatusWithData(sessionId, statusText, null);
    }

    private void publishStatusWithData(String sessionId, String statusText, Object data) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .data(data)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }
}
