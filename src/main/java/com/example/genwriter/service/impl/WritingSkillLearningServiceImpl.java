package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.model.dto.response.LearningResultVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.WritingSkillLearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WritingSkillLearningServiceImpl implements WritingSkillLearningService {

    private final LongTermMemoryService memoryService;
    private final ChatClientFactory chatClientFactory;
    private final LLMConfig llmConfig;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public LearningResultVO analyzeAndStore(String articleContent, String description,
                                            String scope, String projectId, String sessionId) {
        if (articleContent == null || articleContent.isBlank()) {
            return LearningResultVO.builder()
                    .success(false)
                    .message("文章内容不能为空")
                    .extractedCount(0)
                    .storedCount(0)
                    .build();
        }

        log.info("开始分析文章写作技巧: contentLength={}, sessionId={}", articleContent.length(), sessionId);

        try {
            String learningPrompt = buildLearningPrompt(articleContent, description);

            ChatClient client = chatClientFactory.create(properties.getWritingSkillExtraction().getTemperature());
            String response = client.prompt()
                    .user(learningPrompt)
                    .call()
                    .content();

            List<ExtractedSkill> extracted = parseResult(response);
            int maxSkills = properties.getWritingSkillExtraction().getMaxSkillsPerTurn();
            int stored = 0;
            List<LearningResultVO.SkillSummary> skills = new ArrayList<>();

            String effectiveScope = scope != null && !scope.isBlank() ? scope : "GLOBAL";
            String effectiveProjectId = "PROJECT".equals(effectiveScope) ? projectId : null;

            for (ExtractedSkill skill : extracted) {
                if (stored >= maxSkills) break;

                try {
                    String content = buildSkillContent(skill);
                    String importance = skill.importance != null && !skill.importance.isBlank()
                            ? skill.importance : "MEDIUM";
                    memoryService.storeMemory(content, MemoryType.WRITING_TECHNIQUE,
                            effectiveScope, effectiveProjectId, sessionId, importance);
                    stored++;
                    skills.add(new LearningResultVO.SkillSummary(skill.skillName, skill.category));
                } catch (Exception e) {
                    log.warn("存储学习技巧失败: skillName={}, error={}", skill.skillName, e.getMessage());
                }
            }

            log.info("文章技巧学习完成: extracted={}, stored={}", extracted.size(), stored);

            return LearningResultVO.builder()
                    .success(true)
                    .message("学习完成，共提取 " + extracted.size() + " 条技巧，成功保存 " + stored + " 条")
                    .extractedCount(extracted.size())
                    .storedCount(stored)
                    .skills(skills)
                    .build();

        } catch (Exception e) {
            log.error("文章技巧学习失败: sessionId={}", sessionId, e);
            return LearningResultVO.builder()
                    .success(false)
                    .message("学习失败: " + e.getMessage())
                    .extractedCount(0)
                    .storedCount(0)
                    .build();
        }
    }

    private String buildLearningPrompt(String articleContent, String description) {
        String template = llmConfig.getPrompts().getArticleSkillLearningPrompt();

        if (template != null && !template.isBlank()) {
            return template
                    .replace("{articleContent}", articleContent)
                    .replace("{description}", description != null ? description : "");
        }

        return buildDefaultLearningPrompt(articleContent, description);
    }

    private String buildDefaultLearningPrompt(String articleContent, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一位写作技法分析专家。请从用户提供的示例文章中，提取可复用的写作技巧、风格特征和表达手法。\n\n");
        sb.append("# 分析维度\n");
        sb.append("- 叙事节奏：张弛、快慢、悬念设置\n");
        sb.append("- 修辞手法：比喻、排比、通感、反讽等\n");
        sb.append("- 段落结构：长短交替、段落功能划分\n");
        sb.append("- 对话风格：口语化程度、潜台词、对话推动剧情的方式\n");
        sb.append("- 过渡技巧：场景切换、时间跳跃的处理\n");
        sb.append("- 视角控制：人称、距离、聚焦方式\n");
        sb.append("- 描写策略：感官运用、细节选择、动静结合\n");
        sb.append("- 语言质感：词汇偏好、句式节奏、语气特征\n\n");
        sb.append("# 输出格式\n");
        sb.append("纯 JSON 数组，不要 markdown 代码块：\n");
        sb.append("[{\"skillName\": \"技巧名称\", \"category\": \"描写|叙事|对话|结构|修辞|风格|节奏|视角|其他\", \"rule\": \"具体规则描述\", \"applicableScene\": \"适用场景\", \"goodExample\": \"从文章中截取的正例原文片段\", \"importance\": \"HIGH|MEDIUM|LOW\"}]\n\n");

        if (description != null && !description.isBlank()) {
            sb.append("# 用户说明\n").append(description).append("\n\n");
        }

        sb.append("# 示例文章\n").append(articleContent).append("\n\n");
        sb.append("如果文章没有明显的可提取技法，返回空数组 []。");
        return sb.toString();
    }

    private List<ExtractedSkill> parseResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);

            if (!root.isArray()) return List.of();

            return objectMapper.readerForListOf(ExtractedSkill.class).readValue(root);
        } catch (Exception e) {
            log.warn("解析文章技巧学习结果失败: response={}", response, e);
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

        return sb.toString().trim();
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
        public String importance;
    }
}
