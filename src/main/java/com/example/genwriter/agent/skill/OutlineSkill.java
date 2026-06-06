package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

/**
 * 大纲生成 Skill
 */
@RequiredArgsConstructor
public class OutlineSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "outline";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.resolvePrompt(llmConfig.getPrompts().getOutlineSystemPrompt(), "outlineSystemPrompt");
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
        String userInput = (String) context.getOrDefault("userInput", "");
        String ragContext = (String) context.getOrDefault("context", "");
        WritingGenreProfile profile = profile(context, userInput);
        boolean markdownEnabled = markdownEnabled(context);

        StringBuilder sb = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("## 参考信息\n\n").append(ragContext).append("\n\n");
        }
        sb.append("## 用户需求\n\n").append(userInput).append("\n\n");
        if (profile.isNovel()) {
            sb.append("请为以上小说/章节需求设计一份可直接执行的剧情与场景大纲。");
        } else {
            sb.append("请为以上需求设计一份详细的文章大纲。");
        }
        sb.append(WritingPromptConstraints.outlineConstraint(profile));
        sb.append(WritingPromptConstraints.outputFormatConstraint(markdownEnabled));
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "Markdown 大纲，使用 #、##、### 层级";
    }

    private WritingGenreProfile profile(Map<String, Object> context, String userInput) {
        return WritingGenreResolver.profileFromContext(context.get("writingGenre"), userInput);
    }

    private boolean markdownEnabled(Map<String, Object> context) {
        Object value = context.get("markdownEnabled");
        return !(value instanceof Boolean enabled) || enabled;
    }
}
