package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.TavilyWebSearchTool;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.FileStorageService;
import com.example.genwriter.service.LongTermMemoryService;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectAnswerWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.7;

    private final ChatClientFactory chatClientFactory;
    private final DirectAnswerSkill skill;
    private final TavilyWebSearchTool webSearchTool;
    private final KnowledgeBaseTool knowledgeBaseTool;
    private final RedisChatMemory chatMemory;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final ReasoningStreamHelper reasoningStreamHelper;
    private final FileStorageService fileStorageService;

    @PostConstruct
    void init() {
        registry.register(this);
    }

    private ChatClient createChatClient(boolean webSearchEnabled) {
        ToolCallback kbSearchCallback = FunctionToolCallback
                .builder("knowledge_base_search", (java.util.function.Function<KnowledgeBaseTool.KnowledgeSearchInput, String>)
                        knowledgeBaseTool)
                .description("Search the knowledge base for relevant content. Use this tool when a knowledge base ID (kbId) is provided and the question relates to the knowledge base content.")
                .inputType(KnowledgeBaseTool.KnowledgeSearchInput.class)
                .build();

        if (webSearchEnabled) {
            ToolCallback webSearchCallback = FunctionToolCallback
                    .builder("web_search", (java.util.function.Function<TavilyWebSearchTool.WebSearchInput, String>)
                            webSearchTool)
                    .description("Search the web for information. Use this tool when you need to find current information, facts, data, or any content from the internet.")
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
    public String name() {
        return "direct_answer";
    }

    @Override
    public String description() {
        return "直接回答用户问题，可自主调用网络搜索和知识库检索工具获取信息";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String context = (String) state.getOrDefault("context", "");
        String kbId = (String) state.getOrDefault("kbId", "");
        Object webSearchObj = state.getOrDefault("webSearch", true);
        boolean webSearchEnabled = !"false".equals(String.valueOf(webSearchObj));

        // 获取多模态内容
        Object mcObj = state.get("multimodalContent");
        MultimodalContent multimodalContent = mcObj instanceof MultimodalContent ? (MultimodalContent) mcObj : null;

        String nodeId = chainPublisher.publishStart(sessionId, "直接回答",
                ChainNode.Type.EXECUTION, null,
                Map.of("userInput", truncate(userInput, 200), "hasKbId", !kbId.isBlank(),
                        "webSearch", webSearchEnabled));

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
        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型直接回答",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE,
                        "webSearch", webSearchEnabled, "hasKbId", !kbId.isBlank(),
                        "hasImages", multimodalContent != null && multimodalContent.hasImages()), null);
        try {
            if (reasoningStreamHelper.isReasoningModel()) {
                var result = reasoningStreamHelper.stream(sessionId, nodeId,
                        skill.systemPrompt(), userPrompt, TEMPERATURE,
                        chunk -> {
                            contentBuilder.append(chunk);
                            publishContentChunk(sessionId, chunk);
                        });
                reasoningContent = result.reasoningContent();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", result.content() != null ? result.content().length() : 0,
                                "reasoningLength", reasoningContent != null ? reasoningContent.length() : 0));
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
                                conversationId));

                if (longTermMemoryProperties.isEnabled()) {
                    promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                            memoryService,
                            List.of(MemoryType.WRITING_PREFERENCE, MemoryType.DOMAIN_KNOWLEDGE),
                            sessionId));
                }

                promptSpec.stream()
                        .content()
                        .doOnNext(chunk -> {
                            contentBuilder.append(chunk);
                            publishContentChunk(sessionId, chunk);
                        })
                        .then(Mono.just(contentBuilder.toString()))
                        .block(Duration.ofMinutes(5));
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", contentBuilder.length()));
            }
        } catch (Exception e) {
            chainPublisher.publishTraceError(sessionId, llmSpanId, e.getMessage());
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            SessionContextHolder.clear();
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        String fullResponse = contentBuilder.toString();
        log.info("[DirectAnswerWorker] 回答完成: length={}", fullResponse.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", fullResponse.length()), reasoningContent);
        return Map.of("finalOutput", fullResponse);
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("正在生成回答...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE content chunk failed: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
