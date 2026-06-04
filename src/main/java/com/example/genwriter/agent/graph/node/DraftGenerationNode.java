package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.DraftSkill;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.service.FileStorageService;
import com.example.genwriter.service.SseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
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
    private final FileStorageService fileStorageService;

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

        // 获取多模态内容
        Object mcObj = state.value("multimodalContent").orElse(null);
        MultimodalContent multimodalContent = mcObj instanceof MultimodalContent ? (MultimodalContent) mcObj : null;

        log.debug("正文写作: outlineLength={}, hasFeedback={}", outline.length(), !reviewFeedback.isBlank());
        publishStatus(sessionId, "【正文写作】正在根据大纲撰写文章正文...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "outline", outline,
                "context", context,
                "userInput", userInput,
                "reviewFeedback", reviewFeedback
        ));

        // Inject document content into prompt
        if (multimodalContent != null && multimodalContent.hasDocuments()) {
            StringBuilder docContent = new StringBuilder();
            for (var docRef : multimodalContent.getDocumentAttachments()) {
                try {
                    MessageAttachment att = fileStorageService.getById(docRef.getAttachmentId());
                    if (att != null && "COMPLETED".equals(att.getProcessingStatus()) && att.getExtractedText() != null) {
                        docContent.append("\n[附件文档: ").append(docRef.getFileName()).append("]\n");
                        docContent.append(att.getExtractedText()).append("\n[/附件文档]\n");
                    } else if (att != null && !"COMPLETED".equals(att.getProcessingStatus())) {
                        docContent.append("\n[附件文档: ").append(docRef.getFileName()).append(" - 文档正在处理中，暂无法使用其内容]\n");
                    }
                } catch (Exception e) {
                    log.debug("查询附件文档内容失败: {}", e.getMessage());
                }
            }
            if (docContent.length() > 0) {
                userPrompt = userPrompt + "\n\n--- 附件文档内容 ---\n" + docContent;
            }
        }

        final String finalUserPrompt = userPrompt;
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
            var baseSpec = chatClient.prompt()
                    .system(skill.systemPrompt());

            // 多模态支持：如果有图片附件，构建 PromptUserSpec with Media
            ChatClient.ChatClientRequestSpec promptSpec;
            if (multimodalContent != null && multimodalContent.hasImages()) {
                try {
                    Media[] mediaArr = multimodalContent.getImageAttachments().stream()
                            .map(a -> {
                                try {
                                    return new Media(
                                            MimeType.valueOf(a.getMimeType()),
                                            new java.net.URI(a.getFileUrl()).toURL());
                                } catch (Exception ex) {
                                    throw new RuntimeException("Invalid URL: " + a.getFileUrl(), ex);
                                }
                            })
                            .toArray(Media[]::new);
                    promptSpec = baseSpec.user(u -> u.text(finalUserPrompt).media(mediaArr));
                } catch (Exception e) {
                    log.warn("构建多模态消息失败，降级为纯文本: {}", e.getMessage());
                    promptSpec = baseSpec.user(finalUserPrompt);
                }
            } else {
                promptSpec = baseSpec.user(finalUserPrompt);
            }

            promptSpec.stream()
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
