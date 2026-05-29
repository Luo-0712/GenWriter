package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.DraftSkill;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.message.ChainNode;
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
 * 正文写作节点
 * 根据大纲生成文章正文，使用流式输出实时反馈给前端
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerationNode implements NodeAction {
    private static final double TEMPERATURE = 1.5;

    private final ChatClientFactory chatClientFactory;
    private final DraftSkill skill;
    private final SseService sseService;
    private final ThoughtChainPublisher chainPublisher;
    private final ReasoningStreamHelper reasoningStreamHelper;

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String outline = state.value("outline", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String reviewFeedback = state.value("reviewFeedback", String.class).orElse("");

        log.debug("正文写作: outlineLength={}, hasFeedback={}", outline.length(), !reviewFeedback.isBlank());
        publishStatus(sessionId, "【正文写作】正在根据大纲撰写文章正文...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "outline", outline,
                "context", context,
                "userInput", userInput,
                "reviewFeedback", reviewFeedback
        ));

        StringBuilder contentBuilder = new StringBuilder();
        String reasoningContent = null;
        String nodeId = null;

        if (reasoningStreamHelper.isReasoningModel()) {
            nodeId = chainPublisher.publishStart(sessionId, "正文写作",
                    ChainNode.Type.EXECUTION, null, Map.of());
            var result = reasoningStreamHelper.stream(sessionId, nodeId,
                    skill.systemPrompt(), userPrompt, TEMPERATURE,
                    contentBuilder::append);
            reasoningContent = result.reasoningContent();
        } else {
            chatClient.prompt()
                    .system(skill.systemPrompt())
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(contentBuilder::append)
                    .then(Mono.just(contentBuilder.toString()))
                    .block();
        }

        String fullResponse = contentBuilder.toString();
        log.debug("正文写作完成: draftLength={}", fullResponse.length());
        if (nodeId != null) {
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("length", fullResponse.length()), reasoningContent);
        }
        publishStatus(sessionId, "【正文写作】完成，长度=" + fullResponse.length() + " 字符");

        return Map.of(
                "draft", fullResponse,
                "currentNode", "DraftGenerationNode"
        );
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
