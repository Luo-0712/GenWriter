package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.DirectAnswerSkill;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.TavilyWebSearchTool;
import com.example.genwriter.message.SseMessage;
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

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        ToolCallback webSearchCallback = FunctionToolCallback
                .builder("web_search", (java.util.function.Function<TavilyWebSearchTool.WebSearchInput, String>)
                        webSearchTool)
                .description("Search the web for information.")
                .inputType(TavilyWebSearchTool.WebSearchInput.class)
                .build();

        ToolCallback kbSearchCallback = FunctionToolCallback
                .builder("knowledge_base_search", (java.util.function.Function<KnowledgeBaseTool.KnowledgeSearchInput, String>)
                        knowledgeBaseTool)
                .description("Search the knowledge base for relevant content.")
                .inputType(KnowledgeBaseTool.KnowledgeSearchInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(webSearchCallback, kbSearchCallback)
                .build();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");
        String kbId = state.value("kbId", String.class).orElse("");

        log.debug("通用问答: userInput={}, contextLength={}", userInput, context.length());
        publishStatus(sessionId, "【直接回答】正在生成回答...");

        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", context,
                "kbId", kbId
        ));

        String conversationId = sessionId + ":direct";

        StringBuilder contentBuilder = new StringBuilder();
        chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .advisors(new MessageChatMemoryAdvisor(chatMemory))
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

        String fullResponse = contentBuilder.toString();
        log.debug("通用问答完成: responseLength={}", fullResponse.length());
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
