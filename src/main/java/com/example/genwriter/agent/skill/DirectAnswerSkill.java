package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import java.util.Map;

@RequiredArgsConstructor
public class DirectAnswerSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "directAnswer";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.resolvePrompt(llmConfig.getPrompts().getDirectAnswerSystemPrompt(), "directAnswerSystemPrompt");
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        String ragContext = (String) context.getOrDefault("context", "");
        String kbId = (String) context.getOrDefault("kbId", "");

        StringBuilder sb = new StringBuilder();
        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("## 上下文信息\n\n").append(ragContext).append("\n\n");
        }
        if (kbId != null && !kbId.isBlank()) {
            sb.append("## 知识库信息\n\n");
            sb.append("当前可用的知识库ID为: ").append(kbId).append("\n");
            sb.append("如果问题与知识库内容相关，请使用 knowledge_base_search 工具检索，传入此 kbId。\n\n");
        }
        sb.append("## 用户问题\n\n").append(userInput);
        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "直接回答文本";
    }
}
