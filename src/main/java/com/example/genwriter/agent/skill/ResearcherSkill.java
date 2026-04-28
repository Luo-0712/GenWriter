package com.example.genwriter.agent.skill;

import com.example.genwriter.agent.tool.WebSearchResult;
import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 研究调研 Skill
 * 支持计划制定、结果综合、完整性验证三个阶段的 Prompt 构建
 */
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
        // 通用 user prompt，实际使用时按阶段调用专用方法
        String userInput = (String) context.getOrDefault("userInput", "");
        return "## 用户请求\n\n" + userInput;
    }

    @Override
    public String outputFormatDescription() {
        return "JSON 格式的研究报告";
    }

    /**
     * 构建计划阶段的 Prompt
     */
    public String buildPlanningPrompt(String userInput, String existingContext) {
        String template = llmConfig.getPrompts().getResearcherPlanningPrompt();
        return template
                .replace("{userInput}", userInput == null ? "" : userInput)
                .replace("{context}", existingContext == null ? "" : existingContext);
    }

    /**
     * 构建综合阶段的 Prompt
     */
    public String buildSynthesisPrompt(String userInput, List<WebSearchResult> searchResults) {
        String template = llmConfig.getPrompts().getResearcherSynthesisPrompt();
        String resultsText = formatSearchResults(searchResults);
        return template
                .replace("{userInput}", userInput == null ? "" : userInput)
                .replace("{searchResults}", resultsText);
    }

    /**
     * 构建验证阶段的 Prompt
     */
    public String buildVerificationPrompt(String userInput, String researchReport) {
        String template = llmConfig.getPrompts().getResearcherVerificationPrompt();
        return template
                .replace("{userInput}", userInput == null ? "" : userInput)
                .replace("{researchReport}", researchReport == null ? "" : researchReport);
    }

    private String formatSearchResults(List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "（无搜索结果）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            WebSearchResult r = results.get(i);
            sb.append("[").append(i + 1).append("] ").append(r.title()).append("\n");
            sb.append("URL: ").append(r.url()).append("\n");
            sb.append("内容: ").append(r.content()).append("\n\n");
        }
        return sb.toString();
    }
}
