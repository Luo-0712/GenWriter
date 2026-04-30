package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.MemoryExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionServiceImpl implements MemoryExtractionService {

    private final LongTermMemoryService memoryService;
    private final ChatClientFactory chatClientFactory;
    private final LLMConfig llmConfig;
    private final TaskSessionMapper taskSessionMapper;
    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;

    private static final Set<String> GLOBAL_TYPES = Set.of(
            MemoryType.WRITING_PREFERENCE.name(),
            MemoryType.CORRECTION_PATTERN.name(),
            MemoryType.DOMAIN_KNOWLEDGE.name()
    );

    @Override
    @Async("taskExecutor")
    public void extractAsync(String sessionId, String documentId,
                             String userInput, String finalOutput) {
        if (!properties.getExtraction().isEnabled()) {
            return;
        }

        log.info("开始异步提取长期记忆: sessionId={}", sessionId);

        try {
            String projectId = resolveProjectId(sessionId);
            String extractionPrompt = buildExtractionPrompt(userInput, finalOutput, projectId, documentId);

            ChatClient extractionClient = chatClientFactory.create(properties.getExtraction().getTemperature());
            String response = extractionClient.prompt()
                    .user(extractionPrompt)
                    .call()
                    .content();

            List<ExtractedMemory> extracted = parseExtractionResult(response);
            int stored = 0;
            int maxMemories = properties.getExtraction().getMaxMemoriesPerTurn();

            for (ExtractedMemory em : extracted) {
                if (stored >= maxMemories) break;

                try {
                    String scope = determineScope(em.memoryType, projectId, documentId);
                    memoryService.storeMemory(
                            em.content,
                            MemoryType.valueOf(em.memoryType),
                            scope,
                            projectId,
                            documentId,
                            sessionId,
                            em.importance
                    );
                    stored++;
                } catch (Exception e) {
                    log.warn("存储提取记忆失败: type={}, error={}", em.memoryType, e.getMessage());
                }
            }

            log.info("长期记忆提取完成: sessionId={}, extracted={}, stored={}",
                    sessionId, extracted.size(), stored);
        } catch (Exception e) {
            log.error("长期记忆提取失败: sessionId={}", sessionId, e);
        }
    }

    private String resolveProjectId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            TaskSession session = taskSessionMapper.selectById(sessionId);
            return session != null ? session.getProjectId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildExtractionPrompt(String userInput, String finalOutput,
                                         String projectId, String documentId) {
        String template = llmConfig.getPrompts().getMemoryExtractionPrompt();

        if (template != null && !template.isBlank()) {
            return template
                    .replace("{userInput}", userInput != null ? userInput : "")
                    .replace("{finalOutput}", finalOutput != null ? finalOutput : "")
                    .replace("{projectId}", projectId != null ? projectId : "无")
                    .replace("{documentId}", documentId != null ? documentId : "无");
        }

        return buildDefaultExtractionPrompt(userInput, finalOutput, projectId, documentId);
    }

    private String buildDefaultExtractionPrompt(String userInput, String finalOutput,
                                                String projectId, String documentId) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个写作助手的记忆提取系统。分析以下对话，提取值得长期保存的原子事实。\n\n");
        sb.append("当前项目：").append(projectId != null ? projectId : "无").append("\n");
        sb.append("当前文档：").append(documentId != null ? documentId : "无").append("\n\n");
        sb.append("# 提取类别\n");
        sb.append("- WRITING_PREFERENCE: 用户表达的风格/语气/格式偏好（如\"偏好学术风格\"、\"喜欢短段落\"）\n");
        sb.append("- WORLD_SETTING: 故事世界观、背景设定（如\"故事发生在2049年的赛博朋克上海\"）\n");
        sb.append("- CHARACTER_PROFILE: 角色性格、背景、外貌、关系（如\"主角王某是一名退役刑警\"）\n");
        sb.append("- FORESHADOWING: 已埋下待呼应的伏笔（如\"第三章中主角发现了秘密实验室\"）\n");
        sb.append("- CORRECTION_PATTERN: 用户反复提出的修改模式（如\"用户总要求删除冗长的引入段落\"）\n");
        sb.append("- DOMAIN_KNOWLEDGE: 用户分享的特定领域知识\n\n");
        sb.append("# 规则\n");
        sb.append("- 每条事实必须是独立的、完整的原子陈述\n");
        sb.append("- 只提取有新信息价值的内容，不要提取泛泛而谈的对话\n");
        sb.append("- 若无可提取内容，返回空数组\n");
        sb.append("- 对于 WORLD_SETTING / CHARACTER_PROFILE / FORESHADOWING，关注具体的名称、地点、事件\n\n");
        sb.append("# 对话内容\n");
        sb.append("用户：").append(userInput != null ? userInput : "").append("\n");
        sb.append("助手：").append(finalOutput != null ? finalOutput : "").append("\n\n");
        sb.append("# 输出格式（纯 JSON 数组，不要 markdown 代码块）\n");
        sb.append("[{\"memoryType\": \"WRITING_PREFERENCE\", \"content\": \"...\", \"importance\": \"HIGH|MEDIUM|LOW\"}]");
        return sb.toString();
    }

    private String determineScope(String memoryType, String projectId, String documentId) {
        if (GLOBAL_TYPES.contains(memoryType)) {
            return "GLOBAL";
        }
        if (projectId != null && !projectId.isBlank()) {
            return "PROJECT";
        }
        return "GLOBAL";
    }

    private List<ExtractedMemory> parseExtractionResult(String response) {
        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);

            if (!root.isArray()) return List.of();

            return objectMapper.readerForListOf(ExtractedMemory.class).readValue(root);
        } catch (Exception e) {
            log.warn("解析记忆提取结果失败: response={}", response, e);
            return List.of();
        }
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    public record ExtractedMemory(String memoryType, String content, String importance) {}
}
