package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.graph.dto.ReviewResult;
import com.example.genwriter.agent.skill.ReviewSkill;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWorker implements WorkerAgent {

    private static final double TEMPERATURE = 0.1;
    private static final int MAX_REVIEW_ROUNDS = 2;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final ReviewSkill skill;
    private final WorkerRegistry registry;

    private ChatClient chatClient;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
        registry.register(this);
    }

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String description() {
        return "评审文章质量，输出结构化评分和修改建议，决定通过/重写/再润色";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String polishedContent = (String) state.getOrDefault("polishedContent", "");
        String outline = (String) state.getOrDefault("outline", "");
        String userInput = (String) state.getOrDefault("userInput", "");
        int reviewCount = getInt(state, "reviewCount", 0);

        if (reviewCount >= MAX_REVIEW_ROUNDS) {
            log.warn("评审轮次已达上限({})，强制通过", MAX_REVIEW_ROUNDS);
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

        String response = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .call()
                .content();

        ReviewResult result = parseReviewResult(response);
        String verdict = resolveVerdict(result);
        String feedback = result.feedback() != null ? result.feedback() : "请根据评审意见进行修改";

        log.info("评审结果: score={}, verdict={}, reviewCount={}", result.score(), verdict, reviewCount + 1);
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
            log.warn("评审JSON解析失败，兜底: response={}", response, e);
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
