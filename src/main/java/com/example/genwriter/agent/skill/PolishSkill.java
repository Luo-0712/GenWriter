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
        return llmConfig.resolvePrompt(llmConfig.getPrompts().getPolishSystemPrompt(), "polishSystemPrompt");
    }

    public String systemPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        WritingGenreProfile profile = profile(context, userInput);
        boolean markdownEnabled = markdownEnabled(context);
        return systemPrompt()
                + WritingPromptConstraints.systemConstraint(profile)
                + WritingPromptConstraints.outputFormatConstraint(markdownEnabled);
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String content = (String) context.getOrDefault("content", "");
        String reviewFeedback = (String) context.getOrDefault("reviewFeedback", "");
        String userInput = (String) context.getOrDefault("userInput", "");
        WritingGenreProfile profile = profile(context, userInput);
        boolean markdownEnabled = markdownEnabled(context);

        StringBuilder sb = new StringBuilder();
        sb.append("## 待润色文本\n\n").append(content).append("\n\n");

        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("**【评审反馈 - 必须在本次润色中改进】**\n\n")
              .append(reviewFeedback).append("\n\n");
        }

        sb.append("请对以上文本进行润色优化。");
        sb.append(WritingPromptConstraints.polishConstraint(profile));
        sb.append(WritingPromptConstraints.outputFormatConstraint(markdownEnabled));
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "润色后的完整文本";
    }

    private WritingGenreProfile profile(Map<String, Object> context, String userInput) {
        return WritingGenreResolver.profileFromContext(context.get("writingGenre"), userInput);
    }

    private boolean markdownEnabled(Map<String, Object> context) {
        Object value = context.get("markdownEnabled");
        return !(value instanceof Boolean enabled) || enabled;
    }
}
