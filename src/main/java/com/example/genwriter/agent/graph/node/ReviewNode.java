package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.graph.dto.ReviewResult;
import com.example.genwriter.agent.skill.ReviewSkill;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 内容评审节点
 * 对润色后的文章进行质量评审，决定是通过、回退重写还是再次润色
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewNode implements NodeAction {
    private static final double TEMPERATURE = 0.1;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final ReviewSkill skill;
    private final SseService sseService;

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
    }

    private static final int MAX_REVIEW_ROUNDS = 2;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String polishedContent = state.value("polishedContent", String.class).orElse("");
        String draft = state.value("draft", String.class).orElse("");
        String outline = state.value("outline", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        int reviewCount = state.value("reviewCount", Integer.class).orElse(0);

        log.debug("内容评审: reviewCount={}, contentLength={}", reviewCount, polishedContent.length());

        // 超过最大评审轮次，强制通过，避免无限循环
        if (reviewCount >= MAX_REVIEW_ROUNDS) {
            log.warn("评审轮次已达上限({})，强制通过", MAX_REVIEW_ROUNDS);
            publishStatus(sessionId, "【内容评审】轮次已达上限，强制通过");
            return Map.of(
                    "reviewResult", "PASS",
                    "reviewFeedback", "评审轮次已达上限，强制通过",
                    "reviewCount", reviewCount + 1,
                    "finalOutput", polishedContent,
                    "currentNode", "ReviewNode"
            );
        }

        publishStatus(sessionId, "【内容评审】正在评估文章质量...");

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

        log.info("评审结果: score={}, verdict={}, reviewCount={}",
                result.score(), verdict, reviewCount + 1);
        Map<String, Object> reviewDetail = Map.of(
                "综合评分", result.score(),
                "结论", verdict,
                "结构", result.dimensions().structure(),
                "内容", result.dimensions().content(),
                "语言", result.dimensions().language(),
                "逻辑", result.dimensions().logic(),
                "相关性", result.dimensions().relevance(),
                "反馈意见", feedback
        );
        publishStatusWithData(sessionId, "【内容评审】评分=" + result.score() + ", 结论=" + verdict, reviewDetail);

        return Map.of(
                "reviewResult", verdict,
                "reviewFeedback", feedback,
                "reviewCount", reviewCount + 1,
                "finalOutput", "PASS".equals(verdict) ? polishedContent : "",
                "currentNode", "ReviewNode"
        );
    }

    private ReviewResult parseReviewResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            return objectMapper.readValue(json, ReviewResult.class);
        } catch (Exception e) {
            log.warn("评审结果 JSON 解析失败，尝试兜底解析: response={}", response, e);
            return fallbackParse(response);
        }
    }

    /**
     * 根据评分做 verdict 兜底映射
     */
    private String resolveVerdict(ReviewResult result) {
        String rawVerdict = result.verdict();
        if (rawVerdict != null) {
            String upper = rawVerdict.toUpperCase();
            if ("PASS".equals(upper) || "REVISE_DRAFT".equals(upper) || "REVISE_POLISH".equals(upper)) {
                return upper;
            }
        }
        int score = result.score();
        if (score >= 8) return "PASS";
        if (score >= 6) return "REVISE_POLISH";
        return "REVISE_DRAFT";
    }

    /**
     * 兜底解析：从文本中提取关键词
     */
    private ReviewResult fallbackParse(String response) {
        String upper = response.toUpperCase();
        String verdict;
        if (upper.contains("REVISE_DRAFT")) verdict = "REVISE_DRAFT";
        else if (upper.contains("REVISE_POLISH")) verdict = "REVISE_POLISH";
        else if (upper.contains("PASS")) verdict = "PASS";
        else verdict = "PASS";

        int score;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("评分[:：]\\s*(\\d+)").matcher(response);
            score = m.find() ? Integer.parseInt(m.group(1)) : 7;
        } catch (Exception e) {
            score = 7;
        }

        return new ReviewResult(
                score,
                verdict,
                new ReviewResult.Dimensions(score, score, score, score, score),
                "解析失败，使用兜底结果"
        );
    }

    /**
     * 去除可能的 Markdown 代码块标记
     */
    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private void publishStatus(String sessionId, String statusText) {
        publishStatusWithData(sessionId, statusText, null);
    }

    private void publishStatusWithData(String sessionId, String statusText, Object data) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .data(data)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }
}
