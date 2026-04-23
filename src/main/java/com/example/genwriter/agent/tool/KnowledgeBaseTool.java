package com.example.genwriter.agent.tool;

import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.service.RAGPipelineService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索 Tool
 * 供 Agent / LLM 通过 function calling 调用，实现基于向量相似度的知识检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseTool {

    private final RAGPipelineService ragPipelineService;
    private final ObjectMapper objectMapper;

    /**
     * 搜索知识库
     *
     * @param query 用户查询语句
     * @param kbId  知识库ID
     * @param topK  返回最相关的片段数量（默认5）
     * @return 检索结果JSON字符串，包含相关片段的内容和元数据
     */
    public String searchKnowledgeBase(String query, String kbId, int topK) {
        log.info("[KnowledgeBaseTool] 检索知识库: kbId={}, query={}, topK={}", kbId, query, topK);

        if (query == null || query.isBlank()) {
            return "{\"error\":\"查询语句不能为空\"}";
        }
        if (kbId == null || kbId.isBlank()) {
            return "{\"error\":\"知识库ID不能为空\"}";
        }

        try {
            List<KnowledgeChunkDTO> chunks = ragPipelineService.searchAndRetrieve(query, kbId, topK);

            if (chunks == null || chunks.isEmpty()) {
                return "{\"results\":[],\"message\":\"未找到相关知识片段\"}";
            }

            List<SearchResultItem> items = chunks.stream()
                    .map(this::toResultItem)
                    .toList();

            SearchResult result = new SearchResult(items, "success", items.size());
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 检索失败: kbId={}, query={}", kbId, query, e);
            return "{\"error\":\"检索失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 搜索知识库（使用默认topK）
     */
    public String searchKnowledgeBase(String query, String kbId) {
        return searchKnowledgeBase(query, kbId, 5);
    }

    /**
     * 供 Spring AI function calling 调用的统一入口
     */
    public String search(KnowledgeSearchInput input) {
        return searchKnowledgeBase(input.query(), input.kbId(), input.topK());
    }

    private SearchResultItem toResultItem(KnowledgeChunkDTO chunk) {
        return new SearchResultItem(
                chunk.getContent(),
                chunk.getMetadata(),
                chunk.getDistance()
        );
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // -------------------------------------------------------------------------
    // 内部 DTO（仅用于序列化输出）
    // -------------------------------------------------------------------------

    private record SearchResultItem(String content, String metadata, Double relevanceScore) {}

    private record SearchResult(List<SearchResultItem> results, String status, int total) {}
}
