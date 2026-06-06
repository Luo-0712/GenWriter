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

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        String ragContext = (String) context.getOrDefault("context", "");

        StringBuilder sb = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("## 参考信息\n\n").append(ragContext).append("\n\n");
        }
        sb.append("## 用户需求\n\n").append(userInput).append("\n\n");
        sb.append("请为以上需求设计一份详细的文章大纲。");
        sb.append(NovelWritingPromptSupport.outlineConstraint(userInput));
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "Markdown 大纲，使用 #、##、### 层级";
    }
}
