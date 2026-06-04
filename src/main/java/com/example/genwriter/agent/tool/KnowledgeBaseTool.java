package com.example.genwriter.agent.tool;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.service.RAGPipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 知识库检索 Tool
 * 供 Agent / LLM 通过 function calling 调用，实现基于向量相似度的知识检索。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseTool implements Function<KnowledgeBaseTool.KnowledgeSearchInput, String> {

    private final RAGPipelineService ragPipelineService;
    private final ObjectMapper objectMapper;
    private final ThoughtChainPublisher chainPublisher;

    // -------------------------------------------------------------------------
    // Function calling 输入参数
    // -------------------------------------------------------------------------

    public record KnowledgeSearchInput(String query, String kbId, Integer topK) {
        public KnowledgeSearchInput {
            if (topK == null || topK < 1) topK = 5;
            if (topK > 20) topK = 20;
        }
    }

    @Override
    public String apply(KnowledgeSearchInput input) {
        String sessionId = SessionContextHolder.get();
        String traceSpanId = null;
        if (sessionId != null && !sessionId.isBlank()) {
            Map<String, Object> traceInput = new LinkedHashMap<>();
            traceInput.put("query", input != null ? input.query() : "");
            traceInput.put("kbId", input != null ? input.kbId() : "");
            traceInput.put("topK", input != null ? input.topK() : null);
            traceSpanId = chainPublisher.publishToolStart(sessionId, "knowledge_base_search", traceInput);
        }

        if (input == null || input.kbId() == null || input.kbId().isBlank()) {
            publishToolError(sessionId, traceSpanId, "知识库ID不能为空");
            return ToolResult.fail("知识库ID不能为空，请确认是否有可用的知识库").toJson();
        }
        if (input.query() == null || input.query().isBlank()) {
            publishToolError(sessionId, traceSpanId, "检索关键词不能为空");
            return ToolResult.fail("检索关键词不能为空，请提供具体的检索关键词").toJson();
        }

        log.info("[KnowledgeBaseTool] 检索知识库: kbId={}, query={}, topK={}", input.kbId(), input.query(), input.topK());

        try {
            String result = searchKnowledgeBase(input.query(), input.kbId(), input.topK());
            publishToolResult(sessionId, traceSpanId, input, result);
            return result;
        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 知识库检索失败: kbId={}, query={}", input.kbId(), input.query(), e);
            publishToolError(sessionId, traceSpanId, e.getMessage());
            return ToolResult.fail("知识库检索失败: " + e.getMessage()).toJson();
        }
    }

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
            return ToolResult.fail("查询语句不能为空").toJson();
        }
        if (kbId == null || kbId.isBlank()) {
            return ToolResult.fail("知识库ID不能为空").toJson();
        }

        try {
            List<KnowledgeChunkDTO> chunks = ragPipelineService.searchAndRetrieve(query, kbId, topK);

            if (chunks == null || chunks.isEmpty()) {
                return ToolResult.ok("未找到相关知识片段").toJson();
            }

            List<String> sources = chunks.stream()
                    .map(c -> c.getMetadata() != null ? c.getMetadata() : "")
                    .filter(s -> !s.isBlank())
                    .toList();

            String content = chunks.stream()
                    .map(KnowledgeChunkDTO::getContent)
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");

            return ToolResult.ok(content, sources, Map.of("total", chunks.size())).toJson();
        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 检索失败: kbId={}, query={}", kbId, query, e);
            return ToolResult.fail("检索失败: " + e.getMessage()).toJson();
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
        return apply(input);
    }

    private void publishToolResult(String sessionId, String traceSpanId, KnowledgeSearchInput input, String resultJson) {
        if (sessionId == null || sessionId.isBlank() || traceSpanId == null) return;
        try {
            ToolResult result = objectMapper.readValue(resultJson, ToolResult.class);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("query", input.query());
            output.put("kbId", input.kbId());
            output.put("success", result.success());
            output.put("sourcesCount", result.sources() != null ? result.sources().size() : 0);
            output.put("metadata", result.metadata());
            if (!result.success()) {
                output.put("error", result.error());
            }
            chainPublisher.publishToolComplete(sessionId, traceSpanId, "knowledge_base_search", output);
        } catch (Exception e) {
            chainPublisher.publishToolComplete(sessionId, traceSpanId, "knowledge_base_search",
                    Map.of("query", input.query(), "kbId", input.kbId(), "resultLength", resultJson.length()));
        }
    }

    private void publishToolError(String sessionId, String traceSpanId, String error) {
        if (sessionId == null || sessionId.isBlank() || traceSpanId == null) return;
        chainPublisher.publishToolError(sessionId, traceSpanId, "knowledge_base_search", error);
    }

}
