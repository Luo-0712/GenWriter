package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.ResearcherSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.WebSearchTool;
import com.example.genwriter.agent.tool.WebSearchToolCallback;
import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResearcherWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.3;

    private final ChatClientFactory chatClientFactory;
    private final ResearcherSkill skill;
    private final WebSearchTool webSearchTool;
    private final RedisChatMemory chatMemory;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final ObjectMapper objectMapper;
    private final ResearcherProperties properties;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback webSearchCallback = FunctionToolCallback
                .builder("web_search", (java.util.function.Function<WebSearchToolCallback.WebSearchInput, String>)
                        new WebSearchToolCallback(webSearchTool, properties, sseService))
                .description("Search the web for information. Use this tool when you need to find current information, facts, data, or any content from the internet.")
                .inputType(WebSearchToolCallback.WebSearchInput.class)
                .build();

        this.chatClient = chatClientFactory.create(TEMPERATURE)
                .mutate()
                .defaultTools(webSearchCallback)
                .build();
        registry.register(this);
    }

    @Override
    public String name() {
        return "researcher";
    }

    @Override
    public String description() {
        return "执行网络调研：自主决定搜索策略和次数，综合生成结构化研究报告";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String documentId = (String) state.getOrDefault("documentId", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        String existingContext = (String) state.getOrDefault("context", "");

        String nodeId = chainPublisher.publishStart(sessionId, "网络调研",
                ChainNode.Type.TOOL_CALL, null,
                Map.of("userInput", truncate(userInput, 200)));

        publishStatus(sessionId, SseMessage.Type.AI_PLANNING, "【调研】正在分析需求并制定搜索策略...");

        String systemPrompt = skill.systemPrompt();
        String userPrompt = skill.buildUserPrompt(Map.of(
                "userInput", userInput,
                "context", existingContext
        ));

        String conversationId = sessionId + ":researcher";

        SessionContextHolder.set(sessionId);
        String response;
        try {
            var promptSpec = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .advisors(new MessageChatMemoryAdvisor(chatMemory))
                    .advisors(a -> a.param(
                            AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                            conversationId));

            if (longTermMemoryProperties.isEnabled()) {
                promptSpec = promptSpec.advisors(new LongTermMemoryAdvisor(
                        memoryService,
                        List.of(MemoryType.DOMAIN_KNOWLEDGE),
                        sessionId, documentId));
            }

            final var finalPromptSpec = promptSpec;
            response = CompletableFuture.supplyAsync(() -> finalPromptSpec.call().content())
                    .get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            chainPublisher.publishError(sessionId, nodeId, e.getMessage());
            SessionContextHolder.clear();
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        ResearchOutput output = parseResearchOutput(response);
        int searchCount = countSearchRounds(conversationId);

        StringBuilder contextBuilder = new StringBuilder(existingContext);
        if (!existingContext.isBlank()) {
            contextBuilder.append("\n\n");
        }
        contextBuilder.append("【网络调研结果】\n").append(output.report());

        publishStatus(sessionId, SseMessage.Type.AI_EXECUTING,
                "【调研】调研完成，共执行 " + searchCount + " 次搜索");

        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("searchRounds", searchCount, "reportLength", output.report().length(),
                        "sourcesCount", output.sources().size()));

        log.info("[ResearcherWorker] 调研完成: searchRounds={}, reportLength={}, sources={}",
                searchCount, output.report().length(), output.sources().size());

        return Map.of(
                "researchReport", output.report(),
                "researchSources", objectMapper.writeValueAsString(output.sources()),
                "searchRounds", searchCount,
                "context", contextBuilder.toString(),
                "currentNode", "ResearcherWorker"
        );
    }

    private ResearchOutput parseResearchOutput(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);

            String report = root.path("researchReport").asText("");
            if (report.isBlank()) {
                report = root.path("report").asText("");
            }
            if (report.isBlank()) {
                report = response;
            }

            List<Map<String, String>> sources = new ArrayList<>();
            JsonNode sourcesNode = root.path("researchSources");
            if (!sourcesNode.isArray()) {
                sourcesNode = root.path("sources");
            }
            if (sourcesNode.isArray()) {
                for (JsonNode s : sourcesNode) {
                    sources.add(Map.of(
                            "title", s.path("title").asText(""),
                            "url", s.path("url").asText("")
                    ));
                }
            }

            return new ResearchOutput(report, sources);
        } catch (Exception e) {
            log.warn("[ResearcherWorker] 研究报告JSON解析失败，使用原始响应", e);
            return new ResearchOutput(response, List.of());
        }
    }

    private int countSearchRounds(String conversationId) {
        try {
            return (int) chatMemory.getAllMessages(conversationId).stream()
                    .filter(msg -> msg instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm
                            && trm.getResponses().stream().anyMatch(r -> "web_search".equals(r.name())))
                    .count();
        } catch (Exception e) {
            log.debug("统计搜索轮次失败", e);
            return 0;
        }
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private void publishStatus(String sessionId, SseMessage.Type type, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(type)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private record ResearchOutput(String report, List<Map<String, String>> sources) {}
}
