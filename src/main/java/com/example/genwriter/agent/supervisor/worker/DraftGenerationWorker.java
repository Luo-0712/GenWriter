package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.streaming.ReasoningStreamHelper;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.skill.DraftSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.SaveSettingDetailTool;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.UpdateWritingSkillTool;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerationWorker implements WorkerAgent {

    private static final double TEMPERATURE = 1.5;

    private final ChatClientFactory chatClientFactory;
    private final DraftSkill skill;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final UpdateWritingSkillTool updateWritingSkillToolCallback;
    private final SaveSettingDetailTool saveSettingDetailTool;
    private final ReasoningStreamHelper reasoningStreamHelper;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback skillToolCallback = FunctionToolCallback
                .builder("update_writing_skill", (java.util.function.Function<UpdateWritingSkillTool.UpdateWritingSkillInput, String>)
                        updateWritingSkillToolCallback)
                .description("Save a reusable writing skill or technique to long-term memory. Use this tool when the user has taught or demonstrated a writing style, technique, or rule that should be remembered and applied in future writing tasks.")
                .inputType(UpdateWritingSkillTool.UpdateWritingSkillInput.class)
                .build();

        ToolCallback settingDetailCallback = FunctionToolCallback
                .builder("save_setting_detail", (java.util.function.Function<SaveSettingDetailTool.SaveSettingDetailInput, String>)
                        saveSettingDetailTool)
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

        String nodeId = chainPublisher.publishStart(sessionId, "正文写作",
                ChainNode.Type.EXECUTION, null,
                Map.of("hasOutline", !outline.isBlank(), "hasContext", !context.isBlank(),
                        "hasReviewFeedback", !reviewFeedback.isBlank()));

        String userPrompt = skill.buildUserPrompt(Map.of(
                "outline", outline,
                "context", context,
                "userInput", userInput,
                "reviewFeedback", reviewFeedback
        ));

        StringBuilder contentBuilder = new StringBuilder();
        var promptSpec = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt);

        if (longTermMemoryProperties.isEnabled()) {
            promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                    memoryService,
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE,
                            MemoryType.WORLD_SETTING, MemoryType.CHARACTER_PROFILE, MemoryType.FORESHADOWING),
                    sessionId));
        }

        SessionContextHolder.set(sessionId);
        String reasoningContent = null;
        try {
            if (reasoningStreamHelper.isReasoningModel()) {
                var result = reasoningStreamHelper.stream(sessionId, nodeId,
                        skill.systemPrompt(), userPrompt, TEMPERATURE,
                        chunk -> {
                            contentBuilder.append(chunk);
                            publishContentChunk(sessionId, chunk);
                        });
                reasoningContent = result.reasoningContent();
            } else {
                promptSpec.stream()
                        .content()
                        .doOnNext(chunk -> {
                            contentBuilder.append(chunk);
                            publishContentChunk(sessionId, chunk);
                        })
                        .then(Mono.just(contentBuilder.toString()))
                        .block();
            }
        } catch (Exception e) {
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
