package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.skill.OutlineSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.AgentToolSupport;
import com.example.genwriter.agent.tool.SaveSettingDetailTool;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineGenerationWorker implements WorkerAgent {

    private static final double TEMPERATURE = 1.2;

    private final ChatClientFactory chatClientFactory;
    private final OutlineSkill skill;
    private final WorkerRegistry registry;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final SaveSettingDetailTool saveSettingDetailTool;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback settingDetailCallback = FunctionToolCallback
                .builder("save_setting_detail",
                        (BiFunction<SaveSettingDetailTool.SaveSettingDetailInput, ToolContext, String>)
                                saveSettingDetailTool::applyWithContext)
                .description("Save a world setting, character profile, or plot detail (foreshadowing) to long-term memory. Use this tool when you define or introduce setting details during content creation to ensure consistency in future writing.")
                .inputType(SaveSettingDetailTool.SaveSettingDetailInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(settingDetailCallback)
                .build();
        registry.register(this);
    }

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public String description() {
        return "根据用户输入和上下文设计结构化文章大纲";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String context = (String) state.getOrDefault("context", "");

        String nodeId = chainPublisher.publishStart(sessionId, "大纲生成",
                ChainNode.Type.THINKING, null,
                Map.of("userInput", truncate(userInput, 200), "hasContext", !context.isBlank()));

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput, "context", context));

        var promptSpec = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt);

        if (longTermMemoryProperties.isEnabled()) {
            promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                    memoryService,
                    memoryPromptFormatter,
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WORLD_SETTING,
                            MemoryType.CHARACTER_PROFILE, MemoryType.FORESHADOWING),
                    sessionId));
        }

        String response;
        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型生成大纲",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "temperature", TEMPERATURE), null);
        promptSpec = AgentToolSupport.applySessionContext(promptSpec, sessionId, nodeId, name());
        SessionContextHolder.ContextSnapshot contextSnapshot = SessionContextHolder.snapshot();
        try {
            final var finalPromptSpec = promptSpec;
            response = CompletableFuture.supplyAsync(() -> {
                        SessionContextHolder.restore(contextSnapshot);
                        try {
                            return finalPromptSpec.call().content();
                        } finally {
                            SessionContextHolder.clear();
                        }
                    })
                    .get(5, TimeUnit.MINUTES);
            chainPublisher.publishTraceComplete(sessionId, llmSpanId,
                    Map.of("outputLength", response != null ? response.length() : 0));
        } catch (Exception e) {
            chainPublisher.publishTraceError(sessionId, llmSpanId, e.getMessage());
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        log.info("大纲生成完成: length={}", response.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", response.length()));
        return Map.of("outline", response);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
