package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 内容评审 Skill
 */
@RequiredArgsConstructor
public class ReviewSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.getPrompts().getReviewSystemPrompt();
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String polishedContent = (String) context.getOrDefault("polishedContent", "");
        String outline = (String) context.getOrDefault("outline", "");
        String userInput = (String) context.getOrDefault("userInput", "");
        int reviewCount = (int) context.getOrDefault("reviewCount", 0);

        StringBuilder sb = new StringBuilder();
        sb.append("## 待评审文章\n\n").append(polishedContent).append("\n\n");

        if (outline != null && !outline.isBlank()) {
            sb.append("## 原始大纲\n\n").append(outline).append("\n\n");
        }

        sb.append("## 用户原始需求\n\n").append(userInput).append("\n\n");

        if (reviewCount > 0) {
            sb.append("**注意：这是第 ").append(reviewCount + 1)
              .append(" 轮评审，请重点关注之前未解决的问题。**\n\n");
        }

        sb.append("请对以上文章进行质量评审，按指定 JSON 格式输出评审结果。");
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "JSON 格式：{score, verdict, dimensions, feedback}";
    }
}
