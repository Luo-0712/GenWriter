package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.graph.dto.ReviewResult;
import com.example.genwriter.agent.memory.LongTermMemoryAdvisor;
import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.MemoryQueryExtractor;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.profile.AgentPromptRenderer;
import com.example.genwriter.agent.profile.RenderedAgentPrompt;
import com.example.genwriter.agent.skill.WritingGenreResolver;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.AgentToolSupport;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import com.example.genwriter.service.WritingOutputSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.1;
    private static final int MAX_REVIEW_ROUNDS = 2;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final AgentPromptRenderer agentPromptRenderer;
    private final RedisChatMemory chatMemory;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final LongTermMemoryProbeRecorder memoryProbeRecorder;
    private final ThoughtChainPublisher chainPublisher;
    private final MemoryQueryExtractor memoryQueryExtractor;
    private final WritingOutputSettingsService writingOutputSettingsService;

    @PostConstruct
    void init() {
        registry.register(this);
    }

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String description() {
        return "评审文章质量并验证事实准确性，输出结构化评分和修改建议";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String sessionId = (String) state.getOrDefault("sessionId", "");
        String polishedContent = (String) state.getOrDefault("polishedContent", "");
        String outline = (String) state.getOrDefault("outline", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        int reviewCount = getInt(state, "reviewCount", 0);
        boolean webSearchEnabled = AgentToolSupport.isWebSearchEnabled(state.get("webSearch"));
        String writingGenre = (String) state.getOrDefault("writingGenre",
                WritingGenreResolver.resolve(userInput).genre().name());
        boolean markdownEnabled = getBoolean(state, "markdownEnabled",
                writingOutputSettingsService.isMarkdownEnabled());

        if (reviewCount >= MAX_REVIEW_ROUNDS) {
            log.warn("[ReviewWorker] 评审轮次已达上限({})，强制通过", MAX_REVIEW_ROUNDS);
            return Map.of(
                    "reviewResult", "PASS",
                    "reviewFeedback", "评审轮次已达上限，强制通过",
                    "reviewCount", reviewCount + 1
            );
        }

        String nodeId = chainPublisher.publishStart(sessionId, "内容评审",
                ChainNode.Type.THINKING, null,
                Map.of("reviewRound", reviewCount + 1, "contentLength", polishedContent.length()));

        Map<String, Object> skillContext = Map.of(
                "polishedContent", polishedContent,
                "outline", outline,
                "userInput", userInput,
                "reviewCount", reviewCount,
                "writingGenre", writingGenre,
                "markdownEnabled", markdownEnabled
        );
        RenderedAgentPrompt renderedPrompt = agentPromptRenderer.render(name(), skillContext);
        String userPrompt = renderedPrompt.userPrompt();
        userPrompt = AgentToolSupport.appendWebSearchDisabledNotice(userPrompt, webSearchEnabled);

        String conversationId = sessionId + ":review";

        ChatClient chatClient = chatClientFactory.create(TEMPERATURE);

        SessionContextHolder.set(sessionId, nodeId, name());
        String llmSpanId = chainPublisher.publishTraceStart(sessionId, "模型评审",
                AgentTraceEvent.Kind.LLM, nodeId,
                Map.of("promptLength", userPrompt.length(), "webSearch", webSearchEnabled,
                        "reviewRound", reviewCount + 1), null);
        SessionContextHolder.ContextSnapshot contextSnapshot = SessionContextHolder.snapshot();
        String response;
        try {
            var promptSpec = chatClient.prompt()
                    .system(renderedPrompt.systemPrompt())
                    .user(userPrompt);

            promptSpec = AgentToolSupport.applyToolVisibility(
                    promptSpec, webSearchEnabled, false);

            promptSpec = promptSpec.advisors(new MessageChatMemoryAdvisor(chatMemory))
                    .advisors(a -> a.param(
                            AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                            conversationId));

            if (longTermMemoryProperties.isEnabled()) {
                List<String> queries = null;
                if (longTermMemoryProperties.getArticleQueryExtraction().isEnabled()) {
                    queries = new ArrayList<>(memoryQueryExtractor.extractQueries(polishedContent));
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
                        List.of(MemoryType.WRITING_PREFERENCE, MemoryType.CORRECTION_PATTERN),
                        sessionId,
                        queries,
                        memoryProbeRecorder));
            }

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
            SessionContextHolder.clear();
            throw e;
        } finally {
            SessionContextHolder.clear();
        }

        ReviewResult result = parseReviewResult(response);
        String verdict = resolveVerdict(result);
        String feedback = result.feedback() != null ? result.feedback() : "请根据评审意见进行修改";

        chainPublisher.publishComplete(sessionId, nodeId,
                Map.of("score", result.score(), "verdict", verdict,
                        "feedback", truncate(feedback, 200)));

        log.info("[ReviewWorker] 评审结果: score={}, verdict={}, reviewCount={}", result.score(), verdict, reviewCount + 1);
        return Map.of(
                "reviewResult", verdict,
                "reviewFeedback", feedback,
                "reviewCount", reviewCount + 1
        );
    }

    private ReviewResult parseReviewResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            return objectMapper.readValue(json, ReviewResult.class);
        } catch (Exception e) {
            log.warn("[ReviewWorker] 评审JSON解析失败，兜底: response={}", response, e);
            return fallbackParse(response);
        }
    }

    private String resolveVerdict(ReviewResult result) {
        String raw = result.verdict();
        if (raw != null) {
            String upper = raw.toUpperCase();
            if ("PASS".equals(upper) || "REVISE_DRAFT".equals(upper) || "REVISE_POLISH".equals(upper)) {
                return upper;
            }
        }
        int score = result.score();
        if (score >= 8) return "PASS";
        if (score >= 6) return "REVISE_POLISH";
        return "REVISE_DRAFT";
    }

    private ReviewResult fallbackParse(String response) {
        String upper = response.toUpperCase();
        String verdict;
        if (upper.contains("REVISE_DRAFT")) verdict = "REVISE_DRAFT";
        else if (upper.contains("REVISE_POLISH")) verdict = "REVISE_POLISH";
        else verdict = "PASS";

        int score = 7;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("评分[:：]\\s*(\\d+)").matcher(response);
            if (m.find()) score = Integer.parseInt(m.group(1));
        } catch (Exception ignored) {}

        return new ReviewResult(score, verdict,
                new ReviewResult.Dimensions(score, score, score, score, score),
                "解析失败，使用兜底结果");
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private int getInt(Map<String, Object> state, String key, int defaultValue) {
        Object val = state.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    private boolean getBoolean(Map<String, Object> state, String key, boolean defaultValue) {
        Object value = state.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }
}
