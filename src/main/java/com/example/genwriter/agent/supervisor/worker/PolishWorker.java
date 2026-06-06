package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.MemoryQueryExtractor;
import com.example.genwriter.agent.skill.PolishSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.UpdateWritingSkillTool;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.FileStorageService;
import com.example.genwriter.service.LongTermMemoryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolishWorker implements WorkerAgent {

    private static final double TEMPERATURE = 1.0;

    private final ChatClientFactory chatClientFactory;
    private final PolishSkill skill;
    private final WorkerRegistry registry;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final MemoryQueryExtractor memoryQueryExtractor;
    private final UpdateWritingSkillTool updateWritingSkillToolCallback;
    private final ReasoningStreamHelper reasoningStreamHelper;
    private final FileStorageService fileStorageService;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback skillToolCallback = FunctionToolCallback
                .builder("update_writing_skill", (java.util.function.Function<UpdateWritingSkillTool.UpdateWritingSkillInput, String>)
                        updateWritingSkillToolCallback)
                .description("Save a reusable writing skill or technique to long-term memory. Use this tool when the user has taught or demonstrated a writing style, technique, or rule that should be remembered and applied in future writing tasks.")
                .inputType(UpdateWritingSkillTool.UpdateWritingSkillInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(skillToolCallback)
                .build();
        registry.register(this);
    }

    @Override
    public String name() {
        return "polish";
    }

    @Override
    public String description() {
        return "对文章进行润色优化，提升表达质量，保持原意不变";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String draft = (String) state.getOrDefault("draft", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String reviewFeedback = (String) state.getOrDefault("reviewFeedback", "");

        // 获取多模态内容
        Object mcObj = state.get("multimodalContent");
        MultimodalContent multimodalContent = mcObj instanceof MultimodalContent ? (MultimodalContent) mcObj : null;

        String contentToPolish = draft.isBlank() ? userInput : draft;

        String nodeId = chainPublisher.publishStart(sessionId, "润色优化",
                ChainNode.Type.EXECUTION, null,
                Map.of("contentLength", contentToPolish.length(),
                        "hasReviewFeedback", !reviewFeedback.isBlank()));

        String userPrompt = skill.buildUserPrompt(Map.of(
                "content", contentToPolish,
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

        if (longTermMemoryProperties.isEnabled()) {
            List<String> queries = null;
            if (longTermMemoryProperties.getArticleQueryExtraction().isEnabled()) {
                queries = new ArrayList<>(memoryQueryExtractor.extractQueries(contentToPolish));
                if (userInput != null && !userInput.isBlank()) {
                    queries.add(userInput);
                }
                if (queries.isEmpty()) {
                    queries = null;
                }
            }
            promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                    memoryService,
                    memoryPromptFormatter,
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE),
                    sessionId,
                    queries));
        }

        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型润色",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE,
                        "hasImages", multimodalContent != null && multimodalContent.hasImages()), null);
        String reasoningContent = null;
        try {
            if (reasoningStreamHelper.isReasoningModel()) {
                var result = reasoningStreamHelper.stream(sessionId, nodeId,
                        skill.systemPrompt(), userPrompt, TEMPERATURE,
                        contentBuilder::append);
                reasoningContent = result.reasoningContent();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", result.content() != null ? result.content().length() : 0,
                                "reasoningLength", reasoningContent != null ? reasoningContent.length() : 0));
            } else {
                promptSpec.stream()
                        .content()
                        .doOnNext(contentBuilder::append)
                        .then(Mono.just(contentBuilder.toString()))
                        .block();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", contentBuilder.length()));
            }
        } catch (Exception e) {
            chainPublisher.publishTraceError(sessionId, llmSpanId, e.getMessage());
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        String fullResponse = contentBuilder.toString();
        log.info("润色完成: length={}", fullResponse.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", fullResponse.length()), reasoningContent);
        return Map.of("polishedContent", fullResponse);
    }
}
