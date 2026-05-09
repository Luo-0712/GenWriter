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
        String kbId = (String) context.getOrDefault("kbId", "");

        StringBuilder sb = new StringBuilder();
        sb.append("## 调研任务\n\n");
        sb.append(userInput);

        if (existingContext != null && !existingContext.isBlank()) {
            sb.append("\n\n## 已有上下文\n\n");
            sb.append(existingContext);
        }

        if (kbId != null && !kbId.isBlank()) {
            sb.append("\n\n## 知识库信息\n\n");
            sb.append("当前可用的知识库ID为: ").append(kbId).append("\n");
            sb.append("请优先使用 knowledge_base_search 工具检索知识库内容（传入此 kbId），");
            sb.append("再结合 web_search 工具补充网络信息。\n");
            sb.append("综合知识库和网络搜索结果生成研究报告。\n");
        }

        sb.append("\n\n请使用搜索工具进行调研，然后综合所有搜索结果生成结构化研究报告。");
        if (kbId != null && !kbId.isBlank()) {
            sb.append("\n搜索工具包括 knowledge_base_search（知识库检索）和 web_search（网络搜索）。");
        } else {
            sb.append("\n请使用 web_search 工具进行搜索。");
        }
        sb.append("\n最终输出必须按以下 JSON 格式：");
        sb.append("\n{\"researchReport\": \"完整研究报告（Markdown格式）\", \"sources\": [{\"title\": \"...\", \"url\": \"...\"}]}");

        return sb.toString();
    }

    @Override
    public String outputFormatDescription() {
        return "JSON 格式：{researchReport, sources}";
    }
}
