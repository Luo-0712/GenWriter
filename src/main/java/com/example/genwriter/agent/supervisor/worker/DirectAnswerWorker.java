package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.TavilyWebSearchTool;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;
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

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback webSearchCallback = FunctionToolCallback
                .builder("web_search", (java.util.function.Function<TavilyWebSearchTool.WebSearchInput, String>)
                        webSearchTool)
                .description("Search the web for information. Use this tool when you need to find current information, facts, data, or any content from the internet.")
                .inputType(TavilyWebSearchTool.WebSearchInput.class)
                .build();

        ToolCallback kbSearchCallback = FunctionToolCallback
                .builder("knowledge_base_search", (java.util.function.Function<KnowledgeBaseTool.KnowledgeSearchInput, String>)
                        knowledgeBaseTool)
                .description("Search the knowledge base for relevant content. Use this tool when a knowledge base ID (kbId) is provided and the question relates to the knowledge base content.")
                .inputType(KnowledgeBaseTool.KnowledgeSearchInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(webSearchCallback, kbSearchCallback)
                .build();
        registry.register(this);
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

        String nodeId = chainPublisher.publishStart(sessionId, "直接回答",
                ChainNode.Type.EXECUTION, null,
                Map.of("userInput", truncate(userInput, 200), "hasKbId", !kbId.isBlank()));

        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", context,
                "kbId", kbId
        ));

        String conversationId = sessionId + ":direct";

        StringBuilder contentBuilder = new StringBuilder();
        SessionContextHolder.set(sessionId);
        try {
            var promptSpec = chatClient.prompt()
                    .system(skill.systemPrompt())
                    .user(userPrompt)
                    .advisors(new MessageChatMemoryAdvisor(chatMemory))
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
        } catch (Exception e) {
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            SessionContextHolder.clear();
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        String fullResponse = contentBuilder.toString();
        log.info("[DirectAnswerWorker] 回答完成: length={}", fullResponse.length());
        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("length", fullResponse.length()));
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
