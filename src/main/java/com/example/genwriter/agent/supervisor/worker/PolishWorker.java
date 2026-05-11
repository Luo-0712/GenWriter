package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.MemoryQueryExtractor;
import com.example.genwriter.agent.skill.PolishSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
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
    private final SseService sseService;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final MemoryQueryExtractor memoryQueryExtractor;
    private final UpdateWritingSkillTool updateWritingSkillToolCallback;

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

        String contentToPolish = draft.isBlank() ? userInput : draft;

        String nodeId = chainPublisher.publishStart(sessionId, "润色优化",
                ChainNode.Type.EXECUTION, null,
                Map.of("contentLength", contentToPolish.length(),
                        "hasReviewFeedback", !reviewFeedback.isBlank()));

        String userPrompt = skill.buildUserPrompt(Map.of(
                "content", contentToPolish,
                "reviewFeedback", reviewFeedback
        ));

        StringBuilder contentBuilder = new StringBuilder();
        var promptSpec = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt);

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
                    List.of(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE),
                    sessionId,
                    queries));
        }

        SessionContextHolder.set(sessionId);
        try {
            promptSpec.stream()
                    .content()
                    .doOnNext(chunk -> {
                        contentBuilder.append(chunk);
                        publishContentChunk(sessionId, chunk);
                    })
                    .then(Mono.just(contentBuilder.toString()))
                    .block();
        } catch (Exception e) {
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        String fullResponse = contentBuilder.toString();
        log.info("润色完成: length={}", fullResponse.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", fullResponse.length()));
        return Map.of("polishedContent", fullResponse);
    }

    private void publishContentChunk(String sessionId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chunk)
                            .statusText("正在润色优化...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE content chunk failed: {}", e.getMessage());
        }
    }
}
