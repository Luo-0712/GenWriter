package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.graph.dto.ReviewResult;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.skill.ReviewSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.agent.tool.WebSearchTool;
import com.example.genwriter.agent.tool.WebSearchToolCallback;
import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.service.SseService;
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
    private final ReviewSkill skill;
    private final WebSearchTool webSearchTool;
    private final RedisChatMemory chatMemory;
    private final WorkerRegistry registry;
    private final SseService sseService;
    private final ResearcherProperties properties;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        ToolCallback webSearchCallback = FunctionToolCallback
                .builder("web_search", (java.util.function.Function<WebSearchToolCallback.WebSearchInput, String>)
                        new WebSearchToolCallback(webSearchTool, properties, sseService))
                .description("Search the web to verify facts, data, statistics, or any factual claims in the article.")
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

        if (reviewCount >= MAX_REVIEW_ROUNDS) {
            log.warn("[ReviewWorker] 评审轮次已达上限({})，强制通过", MAX_REVIEW_ROUNDS);
            return Map.of(
                    "reviewResult", "PASS",
                    "reviewFeedback", "评审轮次已达上限，强制通过",
                    "reviewCount", reviewCount + 1
            );
        }

        String userPrompt = skill.buildUserPrompt(Map.of(
                "polishedContent", polishedContent,
                "outline", outline,
                "userInput", userInput,
                "reviewCount", reviewCount
        ));

        String conversationId = sessionId + ":review";

        SessionContextHolder.set(sessionId);
        String response;
        try {
            response = CompletableFuture.supplyAsync(() -> chatClient.prompt()
                            .system(skill.systemPrompt())
                            .user(userPrompt)
                            .advisors(new MessageChatMemoryAdvisor(chatMemory))
                            .advisors(a -> a.param(
                                    AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                                    conversationId))
                            .call()
                            .content())
                    .get(5, TimeUnit.MINUTES);
        } finally {
            SessionContextHolder.clear();
        }

        ReviewResult result = parseReviewResult(response);
        String verdict = resolveVerdict(result);
        String feedback = result.feedback() != null ? result.feedback() : "请根据评审意见进行修改";

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

    private int getInt(Map<String, Object> state, String key, int defaultValue) {
        Object val = state.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }
}
