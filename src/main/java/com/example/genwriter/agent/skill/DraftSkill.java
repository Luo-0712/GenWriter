package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 正文写作 Skill
 */
@RequiredArgsConstructor
public class DraftSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "draft";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.resolvePrompt(llmConfig.getPrompts().getDraftSystemPrompt(), "draftSystemPrompt");
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
        String outline = (String) context.getOrDefault("outline", "");
        String ragContext = (String) context.getOrDefault("context", "");
        String userInput = (String) context.getOrDefault("userInput", "");
        String reviewFeedback = (String) context.getOrDefault("reviewFeedback", "");
        WritingGenreProfile profile = profile(context, userInput);
        boolean markdownEnabled = markdownEnabled(context);

        StringBuilder sb = new StringBuilder();
        sb.append("## 大纲\n\n").append(outline).append("\n\n");

        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("## 参考信息\n\n").append(ragContext).append("\n\n");
        }

        sb.append("## 原始需求\n\n").append(userInput).append("\n\n");

        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("**【评审反馈 - 必须在本次写作中改进】**\n\n")
              .append(reviewFeedback).append("\n\n");
        }

        if (profile.isNovel()) {
            sb.append("请根据以上大纲撰写完整的小说章节正文。");
        } else {
            sb.append("请根据以上大纲撰写完整的文章正文。");
        }
        sb.append(WritingPromptConstraints.draftConstraint(profile));
        sb.append(WritingPromptConstraints.outputFormatConstraint(markdownEnabled));
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "Markdown 格式的文章正文";
    }

    private WritingGenreProfile profile(Map<String, Object> context, String userInput) {
        return WritingGenreResolver.profileFromContext(context.get("writingGenre"), userInput);
    }

    private boolean markdownEnabled(Map<String, Object> context) {
        Object value = context.get("markdownEnabled");
        return !(value instanceof Boolean enabled) || enabled;
    }
}
