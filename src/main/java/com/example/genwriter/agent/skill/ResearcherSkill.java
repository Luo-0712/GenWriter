package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public class ResearcherSkill implements AgentSkill {

    private final LLMConfig llmConfig;

    @Override
    public String name() {
        return "researcher";
    }

    @Override
    public String systemPrompt() {
        return llmConfig.getPrompts().getResearcherSystemPrompt();
    }

    @Override
    public String buildUserPrompt(Map<String, Object> context) {
        String userInput = (String) context.getOrDefault("userInput", "");
        String existingContext = (String) context.getOrDefault("context", "");

        StringBuilder sb = new StringBuilder();
        sb.append("## 调研任务\n\n");
        sb.append(userInput);

        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("\n\n## 已有上下文\n\n");
            sb.append(existingContext);
        }

        sb.append("\n\n请使用 web_search 工具进行搜索，然后综合所有搜索结果生成结构化研究报告。");
        sb.append("\n最终输出必须按以下 JSON 格式：");
        sb.append("\n{\"researchReport\": \"完整研究报告（Markdown格式）\", \"sources\": [{\"title\": \"...\", \"url\": \"...\"}]}");

        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "JSON 格式：{researchReport, sources}";
    }
}
