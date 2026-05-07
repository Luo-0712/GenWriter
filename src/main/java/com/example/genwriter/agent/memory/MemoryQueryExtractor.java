package com.example.genwriter.agent.memory;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.config.LLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 从文章中提取关键细节，生成多个长期记忆检索query
 * 用于解决长文章直接作为embedding query导致的长度超限问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryQueryExtractor {

    private final ChatClientFactory chatClientFactory;
    private final LLMConfig llmConfig;
    private final ObjectMapper objectMapper;
    private final LongTermMemoryProperties properties;

    /**
     * 从文章内容中提取需要核查的关键细节，转换为检索query列表
     *
     * @param articleContent 文章正文
     * @return 检索query列表，失败或文章过短时返回空列表
     */
    public List<String> extractQueries(String articleContent) {
        if (articleContent == null || articleContent.isBlank()) {
            return List.of();
        }

        int minLength = properties.getArticleQueryExtraction().getMinArticleLength();
        if (articleContent.length() < minLength) {
            log.debug("文章长度{}小于阈值{}，跳过query提取", articleContent.length(), minLength);
            return List.of();
        }

        try {
            String prompt = buildPrompt(articleContent);
            double temperature = properties.getArticleQueryExtraction().getTemperature();
            ChatClient client = chatClientFactory.create(temperature);

            String response = client.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<String> queries = parseResponse(response);
            int maxQueries = properties.getArticleQueryExtraction().getMaxQueries();
            if (queries.size() > maxQueries) {
                queries = queries.subList(0, maxQueries);
            }

            log.info("从文章提取了{}个检索query", queries.size());
            return queries;
        } catch (Exception e) {
            log.warn("文章关键细节提取失败，降级到空列表: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPrompt(String articleContent) {
        String template = llmConfig.getPrompts().getArticleDetailExtractionPrompt();
        if (template != null && !template.isBlank()) {
            return template.replace("{articleContent}", articleContent);
        }
        return buildDefaultPrompt(articleContent);
    }

    private String buildDefaultPrompt(String articleContent) {
        return """
                你是一个文章细节提取助手。请从给定的文章中提取3-5个关键细节或核查要点，这些细节可能需要参考历史记忆来确认一致性。

                关注以下类型的细节：
                - 人物设定（姓名、身份、性格、关系等）
                - 时间线与历史事件
                - 地点与场景描述
                - 专有名词、术语
                - 风格或语气特征
                - 前后文可能需要呼应的情节

                输出格式：纯JSON数组，不要markdown代码块
                ["核查要点1", "核查要点2", "核查要点3"]

                文章：
                """ + articleContent;
    }

    private List<String> parseResponse(String response) {
        List<String> result = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return result;
        }

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);

            if (!root.isArray()) {
                return result;
            }

            for (JsonNode node : root) {
                if (node.isTextual()) {
                    String text = node.asText().trim();
                    if (!text.isBlank()) {
                        result.add(text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析query提取结果失败: response={}", response, e);
        }

        return result;
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
