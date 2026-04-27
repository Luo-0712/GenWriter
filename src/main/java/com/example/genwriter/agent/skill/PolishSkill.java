package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 润色 Skill
 */
@RequiredArgsConstructor
public class PolishSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "polish";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.getPrompts().getPolishSystemPrompt();
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String content = (String) context.getOrDefault("content", "");
        String reviewFeedback = (String) context.getOrDefault("reviewFeedback", "");

        StringBuilder sb = new StringBuilder();
        sb.append("## 待润色文本\n\n").append(content).append("\n\n");

        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("**【评审反馈 - 必须在本次润色中改进】**\n\n")
              .append(reviewFeedback).append("\n\n");
        }

        sb.append("请对以上文本进行润色优化。");
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "润色后的完整文本";
    }
}
