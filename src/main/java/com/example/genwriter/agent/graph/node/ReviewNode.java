package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容评审节点
 * 对润色后的文章进行质量评审，决定是通过、回退重写还是再次润色
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewNode implements NodeAction {

    private final ChatClient chatClient;

    private static final int MAX_REVIEW_ROUNDS = 2;
    private static final Pattern VERDICT_PATTERN = Pattern.compile("评审结论[：:]\\s*(PASS|REVISE_DRAFT|REVISE_POLISH)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEEDBACK_PATTERN = Pattern.compile("修改建议[：:]\\s*(.+?)(?=\\n|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String polishedContent = state.value("polishedContent", String.class).orElse("");
        String draft = state.value("draft", String.class).orElse("");
        String outline = state.value("outline", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        int reviewCount = state.value("reviewCount", Integer.class).orElse(0);

        log.debug("内容评审: reviewCount={}, contentLength={}", reviewCount, polishedContent.length());

        // 超过最大评审轮次，强制通过，避免无限循环
        if (reviewCount >= MAX_REVIEW_ROUNDS) {
            log.warn("评审轮次已达上限({})，强制通过", MAX_REVIEW_ROUNDS);
            return Map.of(
                    "reviewResult", "PASS",
                    "reviewFeedback", "评审轮次已达上限，强制通过",
                    "reviewCount", reviewCount + 1,
                    "finalOutput", polishedContent,
                    "currentNode", "ReviewNode"
            );
        }

        String prompt = buildPrompt(polishedContent, draft, outline, userInput);
        String response = chatClient.prompt()
                .system("你是一位资深的内容评审专家，擅长从结构、内容、语言、逻辑等维度评估文章质量。请严格按指定格式输出评审结果。")
                .user(prompt)
                .call()
                .content();

        String verdict = parseVerdict(response);
        String feedback = parseFeedback(response);

        // 如果解析失败，默认通过
        if (verdict == null) {
            log.warn("评审结论解析失败，默认通过. response={}", response);
            verdict = "PASS";
            feedback = "解析失败，默认通过";
        }

        log.info("评审结果: verdict={}, reviewCount={}", verdict, reviewCount + 1);

        return Map.of(
                "reviewResult", verdict,
                "reviewFeedback", feedback,
                "reviewCount", reviewCount + 1,
                "finalOutput", "PASS".equals(verdict) ? polishedContent : "",
                "currentNode", "ReviewNode"
        );
    }

    private String buildPrompt(String polishedContent, String draft, String outline, String userInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下文章进行质量评审。\n\n");

        if (outline != null && !outline.isBlank()) {
            sb.append("【原始大纲】\n").append(outline).append("\n\n");
        }
        sb.append("【用户原始需求】\n").append(userInput).append("\n\n");
        sb.append("【待评审文章】\n").append(polishedContent).append("\n\n");

        sb.append("""
                请从以下维度进行评估：
                1. 结构完整性：是否覆盖了大纲的所有要点（如有大纲）
                2. 内容质量：信息是否准确、有深度、有价值
                3. 语言表达：是否流畅、自然、专业
                4. 逻辑连贯性：段落之间过渡是否自然，论证是否充分
                5. 符合需求：是否准确满足用户的原始需求

                请严格按以下格式输出（不要输出额外内容）：

                总体评分：[1-10分]
                评审结论：[PASS 或 REVISE_DRAFT 或 REVISE_POLISH]
                修改建议：[如果结论是 PASS，写"无"；否则给出具体、可操作的修改建议]

                结论说明：
                - PASS：文章质量合格，可以直接发布
                - REVISE_DRAFT：文章存在结构性或内容层面的重大问题，需要回到正文生成阶段重新撰写
                - REVISE_POLISH：文章整体结构和内容不错，但在语言表达或细节上有问题，需要再次润色
                """);
        return sb.toString();
    }

    private String parseVerdict(String response) {
        Matcher matcher = VERDICT_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        // 兼容简写
        if (response.toUpperCase().contains("PASS")) return "PASS";
        if (response.toUpperCase().contains("REVISE_DRAFT")) return "REVISE_DRAFT";
        if (response.toUpperCase().contains("REVISE_POLISH")) return "REVISE_POLISH";
        return null;
    }

    private String parseFeedback(String response) {
        Matcher matcher = FEEDBACK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "请根据评审意见进行修改";
    }
}
