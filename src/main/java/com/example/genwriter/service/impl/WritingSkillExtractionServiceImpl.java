package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.WritingSkillExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WritingSkillExtractionServiceImpl implements WritingSkillExtractionService {

    private final LongTermMemoryService memoryService;
    private final ChatClientFactory chatClientFactory;
    private final LLMConfig llmConfig;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    @Async("taskExecutor")
    public void extractAsync(String sessionId, String userInput, String assistantOutput, String writingType) {
        if (!properties.getWritingSkillExtraction().isEnabled()) {
            return;
        }

        log.info("开始异步提取写作技巧: sessionId={}", sessionId);

        try {
            String extractionPrompt = buildExtractionPrompt(userInput, assistantOutput, writingType);

            ChatClient extractionClient = chatClientFactory.create(properties.getWritingSkillExtraction().getTemperature());
            String response = extractionClient.prompt()
                    .user(extractionPrompt)
                    .call()
                    .content();

            List<ExtractedSkill> extracted = parseExtractionResult(response);
            int stored = 0;
            int maxSkills = properties.getWritingSkillExtraction().getMaxSkillsPerTurn();

            for (ExtractedSkill skill : extracted) {
                if (stored >= maxSkills) break;
                if (skill.skillName == null || skill.skillName.isBlank()
                        || skill.rule == null || skill.rule.isBlank()) {
                    continue;
                }

                try {
                    String content = buildSkillContent(skill);
                    String importance = skill.importance != null && !skill.importance.isBlank()
                            ? skill.importance : "MEDIUM";
                    memoryService.storeMemory(content, MemoryType.WRITING_TECHNIQUE, "GLOBAL", null,
                            sessionId, importance, buildSkillMetadata(skill, sessionId, importance));
                    stored++;
                } catch (Exception e) {
                    log.warn("存储提取写作技巧失败: skillName={}, error={}", skill.skillName, e.getMessage());
                }
            }

            log.info("写作技巧提取完成: sessionId={}, extracted={}, stored={}",
                    sessionId, extracted.size(), stored);
        } catch (Exception e) {
            log.error("写作技巧提取失败: sessionId={}", sessionId, e);
        }
    }

    private String buildExtractionPrompt(String userInput, String assistantOutput, String writingType) {
        String template = llmConfig.resolvePrompt(llmConfig.getPrompts().getWritingSkillExtractionPrompt(), "writingSkillExtractionPrompt");

        if (template != null && !template.isBlank()) {
            return template
                    .replace("{userInput}", userInput != null ? userInput : "")
                    .replace("{assistantOutput}", assistantOutput != null ? assistantOutput : "")
                    .replace("{writingType}", writingType != null ? writingType : "CREATE");
        }

        return buildDefaultExtractionPrompt(userInput, assistantOutput, writingType);
    }

    private String buildDefaultExtractionPrompt(String userInput, String assistantOutput, String writingType) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位写作技巧提取专家。分析以下用户与写作助手的交互，判断用户是否表达了可抽象为通用写作技巧的改进要求。\n\n");
        sb.append("# 提取标准\n");
        sb.append("只提取满足以下条件的技巧：\n");
        sb.append("1. 用户明确指出了写作中的问题或改进方向\n");
        sb.append("2. 该要求可抽象为一条可复用的写作规则（不限于本次内容）\n");
        sb.append("3. 不是针对特定事实内容的修改（如\"把主角名字改成张三\"不提取）\n\n");
        sb.append("# 输出格式\n");
        sb.append("纯 JSON 数组，不要 markdown 代码块：\n");
        sb.append("[{\"skillName\": \"技巧名称\", \"category\": \"描写|叙事|对话|结构|修辞|风格|其他\", \"rule\": \"具体规则描述\", \"applicableScene\": \"适用场景\", \"goodExample\": \"正例\", \"badExample\": \"反例\", \"importance\": \"HIGH|MEDIUM|LOW\"}]\n\n");
        sb.append("# 交互内容\n");
        sb.append("用户输入：").append(userInput != null ? userInput : "").append("\n");
        sb.append("助手输出：").append(assistantOutput != null ? assistantOutput : "").append("\n");
        sb.append("写作类型：").append(writingType != null ? writingType : "CREATE").append("\n\n");
        sb.append("若无可提取技巧，返回空数组 []。");
        return sb.toString();
    }

    private List<ExtractedSkill> parseExtractionResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);

            if (!root.isArray()) return List.of();

            return objectMapper.readerForListOf(ExtractedSkill.class).readValue(root);
        } catch (Exception e) {
            log.warn("解析写作技巧提取结果失败: response={}", response, e);
            return List.of();
        }
    }

    private String buildSkillContent(ExtractedSkill skill) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 技巧名称\n").append(skill.skillName).append("\n\n");

        if (skill.category != null && !skill.category.isBlank()) {
            sb.append("## 分类\n").append(skill.category).append("\n\n");
        }

        sb.append("## 规则\n").append(skill.rule).append("\n\n");

        if (skill.applicableScene != null && !skill.applicableScene.isBlank()) {
            sb.append("## 适用场景\n").append(skill.applicableScene).append("\n\n");
        }

        if (skill.goodExample != null && !skill.goodExample.isBlank()) {
            sb.append("## 正例\n").append(skill.goodExample).append("\n\n");
        }

        if (skill.badExample != null && !skill.badExample.isBlank()) {
            sb.append("## 反例\n").append(skill.badExample).append("\n\n");
        }

        return sb.toString().trim();
    }

    private Map<String, Object> buildSkillMetadata(ExtractedSkill skill, String sessionId, String importance) {
        Map<String, Object> facets = new LinkedHashMap<>();
        facets.put("skillName", skill.skillName);
        facets.put("category", safe(skill.category));
        facets.put("rule", skill.rule);
        facets.put("applicableScene", safe(skill.applicableScene));
        facets.put("goodExample", safe(skill.goodExample));
        facets.put("badExample", safe(skill.badExample));

        return Map.of(
                "title", safe(skill.skillName),
                "summary", safe(skill.rule),
                "keywords", List.of(safe(skill.skillName), safe(skill.category), "写作技巧"),
                "facets", facets,
                "source", Map.of(
                        "service", "writing_skill_extraction",
                        "sessionId", safe(sessionId),
                        "importance", importance
                )
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    public static class ExtractedSkill {
        public String skillName;
        public String category;
        public String rule;
        public String applicableScene;
        public String goodExample;
        public String badExample;
        public String importance;
    }
}
