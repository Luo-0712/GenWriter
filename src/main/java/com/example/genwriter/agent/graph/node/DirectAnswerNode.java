package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.TavilyWebSearchTool;
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
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.model.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectAnswerNode implements NodeAction {
    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final DirectAnswerSkill skill;
    private final TavilyWebSearchTool webSearchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final RedisChatMemory chatMemory;
    private final SseService sseService;
    private final ThoughtChainPublisher chainPublisher;
    private final ReasoningStreamHelper reasoningStreamHelper;
    private final FileStorageService fileStorageService;

    private ChatClient createChatClient(boolean webSearchEnabled) {
        ToolCallback kbSearchCallback = FunctionToolCallback
                .builder("knowledge_base_search", (java.util.function.Function<KnowledgeBaseTool.KnowledgeSearchInput, String>)
                        knowledgeBaseTool)
                .description("Search the knowledge base for relevant content.")
                .inputType(KnowledgeBaseTool.KnowledgeSearchInput.class)
                .build();

        if (webSearchEnabled) {
            ToolCallback webSearchCallback = FunctionToolCallback
                    .builder("web_search", (java.util.function.Function<TavilyWebSearchTool.WebSearchInput, String>)
                            webSearchTool)
                    .description("Search the web for information.")
                    .inputType(TavilyWebSearchTool.WebSearchInput.class)
                    .build();
            return chatClientFactory.create(TEMPERATURE)
                    .mutate()
                    .defaultTools(webSearchCallback, kbSearchCallback)
                    .build();
        } else {
            return chatClientFactory.create(TEMPERATURE)
                    .mutate()
                    .defaultTools(kbSearchCallback)
                    .build();
        }
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");
        String kbId = state.value("kbId", String.class).orElse("");
        String webSearchStr = state.value("webSearch", String.class).orElse("true");
        boolean webSearchEnabled = !"false".equalsIgnoreCase(webSearchStr);

        // 获取多模态内容
        Object mcObj = state.value("multimodalContent").orElse(null);
        MultimodalContent multimodalContent = mcObj instanceof MultimodalContent ? (MultimodalContent) mcObj : null;

        log.debug("通用问答: userInput={}, contextLength={}", userInput, context.length());
        publishStatus(sessionId, "【直接回答】正在生成回答...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", context,
                "kbId", kbId
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

        String conversationId = sessionId + ":direct";
        final String finalUserPrompt = userPrompt;

        StringBuilder contentBuilder = new StringBuilder();
        String reasoningContent = null;
        String nodeId = null;

        if (reasoningStreamHelper.isReasoningModel()) {
            nodeId = chainPublisher.publishStart(sessionId, "直接回答",
                    ChainNode.Type.EXECUTION, null, Map.of());
            var result = reasoningStreamHelper.stream(sessionId, nodeId,
                    skill.systemPrompt(), userPrompt, TEMPERATURE,
                    chunk -> {
                        contentBuilder.append(chunk);
                        publishContentChunk(sessionId, chunk);
                    });
            reasoningContent = result.reasoningContent();
        } else {
            ChatClient chatClient = createChatClient(webSearchEnabled);
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

            promptSpec.advisors(new MessageChatMemoryAdvisor(chatMemory))
                    .advisors(a -> a.param(
                            AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                            conversationId))
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
                        contentBuilder.append(chunk);
                        publishContentChunk(sessionId, chunk);
                    })
                    .then(Mono.just(contentBuilder.toString()))
                    .block();
        }

        String fullResponse = contentBuilder.toString();
        log.debug("通用问答完成: responseLength={}", fullResponse.length());
        if (nodeId != null) {
            chainPublisher.publishComplete(sessionId, nodeId,
                    Map.of("length", fullResponse.length()), reasoningContent);
        }
        publishStatus(sessionId, "【直接回答】完成，长度=" + fullResponse.length() + " 字符");

        return Map.of(
                "finalOutput", fullResponse,
                "currentNode", "DirectAnswerNode"
        );
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
            log.debug("SSE 内容推送失败: {}", e.getMessage());
        }
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
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
