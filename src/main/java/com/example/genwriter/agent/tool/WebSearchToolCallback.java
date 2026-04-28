package com.example.genwriter.agent.tool;

import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Function;

@Slf4j
public class WebSearchToolCallback implements Function<WebSearchToolCallback.WebSearchInput, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebSearchTool webSearchTool;
    private final ResearcherProperties properties;
    private final SseService sseService;

    public record WebSearchInput(String query, Integer topK) {
        public WebSearchInput {
            if (topK == null || topK < 1) topK = 5;
            if (topK > 10) topK = 10;
        }
    }

    public WebSearchToolCallback(WebSearchTool webSearchTool, ResearcherProperties properties, SseService sseService) {
        this.webSearchTool = webSearchTool;
        this.properties = properties;
        this.sseService = sseService;
    }

    @Override
    public String apply(WebSearchInput input) {
        if (input.query() == null || input.query().isBlank()) {
            return "{\"error\": \"搜索关键词不能为空，请提供具体的搜索关键词\"}";
        }

        log.info("[WebSearchTool] 执行搜索: query={}, topK={}", input.query(), input.topK());
        publishSearchStatus(input.query(), "executing");

        try {
            int effectiveTopK = Math.min(input.topK(), properties.getMaxSearchResultsPerQuery());
            List<WebSearchResult> results = webSearchTool.search(input.query(), effectiveTopK);
            publishSearchStatus(input.query(), "completed");
            return formatResults(results);
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索失败: query={}", input.query(), e);
            publishSearchStatus(input.query(), "failed");
            return "{\"error\": \"搜索失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String formatResults(List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "{\"results\": [], \"message\": \"未找到相关结果\"}";
        }
        try {
            var items = results.stream()
                    .map(r -> new SearchResultItem(r.title(), r.url(), r.snippet(), r.source()))
                    .toList();
            return OBJECT_MAPPER.writeValueAsString(new SearchResult(items, items.size()));
        } catch (Exception e) {
            log.error("[WebSearchTool] 格式化搜索结果失败", e);
            return "{\"results\": [], \"error\": \"格式化失败\"}";
        }
    }

    private void publishSearchStatus(String query, String status) {
        String sessionId = SessionContextHolder.get();
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_EXECUTING)
                    .payload(SseMessage.Payload.builder()
                            .statusText("【搜索】" + status + ": " + query)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 搜索状态推送失败: {}", e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record SearchResultItem(String title, String url, String snippet, String source) {}
    private record SearchResult(List<SearchResultItem> results, int total) {}
}
