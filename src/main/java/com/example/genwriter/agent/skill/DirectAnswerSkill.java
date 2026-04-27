package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 通用问答 Skill
 */
@RequiredArgsConstructor
public class DirectAnswerSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "directAnswer";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.getPrompts().getDirectAnswerSystemPrompt();
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        String ragContext = (String) context.getOrDefault("context", "");

        StringBuilder sb = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("## 上下文信息\n\n").append(ragContext).append("\n\n");
        }
        sb.append("## 用户问题\n\n").append(userInput);
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "直接回答文本";
    }
}
