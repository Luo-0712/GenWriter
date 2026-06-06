package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.skill.DraftSkill;
import com.example.genwriter.agent.skill.WritingGenreResolver;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.AgentToolSupport;
import com.example.genwriter.agent.tool.SaveSettingDetailTool;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.UpdateWritingSkillTool;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.FileStorageService;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.WritingOutputSettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerationWorker implements WorkerAgent {

    private static final double TEMPERATURE = 1.5;

    private final ChatClientFactory chatClientFactory;
    private final DraftSkill skill;
    private final WorkerRegistry registry;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final UpdateWritingSkillTool updateWritingSkillToolCallback;
    private final SaveSettingDetailTool saveSettingDetailTool;
    private final ReasoningStreamHelper reasoningStreamHelper;
    private final FileStorageService fileStorageService;
    private final WritingOutputSettingsService writingOutputSettingsService;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback skillToolCallback = FunctionToolCallback
                .builder("update_writing_skill",
                        (BiFunction<UpdateWritingSkillTool.UpdateWritingSkillInput, ToolContext, String>)
                                updateWritingSkillToolCallback::applyWithContext)
                .description("Save a reusable writing skill or technique to long-term memory. Use this tool when the user has taught or demonstrated a writing style, technique, or rule that should be remembered and applied in future writing tasks.")
                .inputType(UpdateWritingSkillTool.UpdateWritingSkillInput.class)
                .build();

        ToolCallback settingDetailCallback = FunctionToolCallback
                .builder("save_setting_detail",
                        (BiFunction<SaveSettingDetailTool.SaveSettingDetailInput, ToolContext, String>)
                                saveSettingDetailTool::applyWithContext)
                .description("Save a world setting, character profile, or plot detail (foreshadowing) to long-term memory. Use this tool when you define or introduce setting details during content creation to ensure consistency in future writing.")
                .inputType(SaveSettingDetailTool.SaveSettingDetailInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(skillToolCallback, settingDetailCallback)
                .build();
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
        String writingType = (String) state.getOrDefault("writingType", "AUTO");
        String existingDraft = (String) state.getOrDefault("draft", "");
        String writingGenre = (String) state.getOrDefault("writingGenre",
                WritingGenreResolver.resolve(userInput).genre().name());
        boolean markdownEnabled = getBoolean(state, "markdownEnabled",
                writingOutputSettingsService.isMarkdownEnabled());

        // 获取多模态内容
        Object mcObj = state.get("multimodalContent");
        MultimodalContent multimodalContent = mcObj instanceof MultimodalContent ? (MultimodalContent) mcObj : null;

        String nodeId = chainPublisher.publishStart(sessionId, "正文写作",
                ChainNode.Type.EXECUTION, null,
                Map.of("hasOutline", !outline.isBlank(), "hasContext", !context.isBlank(),
                        "hasReviewFeedback", !reviewFeedback.isBlank(),
                        "hasExistingDraft", !existingDraft.isBlank()));

        Map<String, Object> skillContext = Map.of(
                "outline", outline,
                "context", context,
                "userInput", userInput,
                "reviewFeedback", reviewFeedback,
                "writingGenre", writingGenre,
                "markdownEnabled", markdownEnabled
        );
        String systemPrompt = skill.systemPrompt(skillContext);
        String userPrompt = skill.buildUserPrompt(skillContext);

        if ("CONTINUE".equalsIgnoreCase(writingType) && !existingDraft.isBlank()) {
            userPrompt = userPrompt + "\n\n--- 已有文稿，请在此基础上自然续写 ---\n"
                    + existingDraft
                    + "\n\n续写要求：保留已有文稿，不要从头重写；在其后延展内容，并响应用户本次迭代指令。";
        }

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
                .system(systemPrompt);

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
            promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                    memoryService,
                    memoryPromptFormatter,
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE,
                            MemoryType.WORLD_SETTING, MemoryType.CHARACTER_PROFILE, MemoryType.FORESHADOWING),
                    sessionId));
        }

        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型撰写正文",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE,
                        "hasImages", multimodalContent != null && multimodalContent.hasImages()), null);
        promptSpec = AgentToolSupport.applySessionContext(promptSpec, sessionId, nodeId, name());
        SessionContextHolder.ContextSnapshot contextSnapshot = SessionContextHolder.snapshot();
        String reasoningContent = null;
        try {
            if (reasoningStreamHelper.isReasoningModel()) {
                log.info("[DraftGenerationWorker] Reasoning stream path uses raw streaming client; tool calls are unavailable and setting extraction fallback will persist story settings. sessionId={}", sessionId);
                var result = reasoningStreamHelper.stream(sessionId, nodeId,
                        systemPrompt, userPrompt, TEMPERATURE,
                        contentBuilder::append);
                reasoningContent = result.reasoningContent();
                chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                        Map.of("outputLength", result.content() != null ? result.content().length() : 0,
                                "reasoningLength", reasoningContent != null ? reasoningContent.length() : 0));
            } else {
                final var finalPromptSpec = promptSpec;
                CompletableFuture.supplyAsync(() -> {
                            SessionContextHolder.restore(contextSnapshot);
                            try {
                                return finalPromptSpec.stream()
                                        .content()
                                        .doOnNext(contentBuilder::append)
                                        .then(Mono.just(contentBuilder.toString()))
                                        .block();
                            } finally {
                                SessionContextHolder.clear();
                            }
                        })
                        .get(5, TimeUnit.MINUTES);
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
        log.info("正文写作完成: length={}", fullResponse.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", fullResponse.length()), reasoningContent);
        return Map.of("draft", fullResponse);
    }

    private boolean getBoolean(Map<String, Object> state, String key, boolean defaultValue) {
        Object value = state.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }
}
