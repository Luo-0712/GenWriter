package com.example.genwriter.agent.tool;

import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.config.WebSearchProperties;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;


/**
 * Tavily 网页搜索工具实现
 * 调用 Tavily Search API (https://api.tavily.com/search)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyWebSearchTool implements WebSearchTool, Function<TavilyWebSearchTool.WebSearchInput, String> {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    private final WebSearchProperties webSearchProperties;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;
    private final ResearcherProperties properties;
    private final SseService sseService;

    private RestTemplate restTemplate;

    // -------------------------------------------------------------------------
    // Function calling 输入参数
    // -------------------------------------------------------------------------

    public record WebSearchInput(String query, Integer topK) {
        public WebSearchInput {
            if (topK == null || topK < 1) topK = 5;
            if (topK > 10) topK = 10;
        }
    }

    @PostConstruct
    void init() {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(webSearchProperties.getTimeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(webSearchProperties.getTimeoutSeconds()))
                .build();
    }

    // -------------------------------------------------------------------------
    // Function calling 接口
    // -------------------------------------------------------------------------

    @Override
    public String apply(WebSearchInput input) {
        if (input.query() == null || input.query().isBlank()) {
            return ToolResult.fail("搜索关键词不能为空，请提供具体的搜索关键词").toJson();
        }

        log.info("[WebSearchTool] 执行搜索: query={}, topK={}", input.query(), input.topK());
        publishSearchStatus(input.query(), "executing");

        try {
            int effectiveTopK = Math.min(input.topK(), properties.getMaxSearchResultsPerQuery());
            List<WebSearchResult> results = search(input.query(), effectiveTopK);
            publishSearchStatus(input.query(), "completed");
            return formatResults(results);
        } catch (Exception e) {
            log.error("[WebSearchTool] 搜索失败: query={}", input.query(), e);
            publishSearchStatus(input.query(), "failed");
            return ToolResult.fail("搜索失败: " + e.getMessage()).toJson();
        }
    }

    @Override
    public List<WebSearchResult> search(String query, int topK) {
        String apiKey = webSearchProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[TavilyWebSearch] API密钥未配置，跳过搜索: query={}", query);
            return Collections.emptyList();
        }

        if (query == null || query.isBlank()) {
            log.warn("[TavilyWebSearch] 查询语句为空");
            return Collections.emptyList();
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "api_key", apiKey,
                    "query", query,
                    "max_results", Math.min(topK, 10),
                    "search_depth", "basic"
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            log.info("[TavilyWebSearch] 搜索请求: query={}, topK={}", query, topK);
            ResponseEntity<String> response = restTemplate.postForEntity(TAVILY_API_URL, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("[TavilyWebSearch] 搜索失败: status={}", response.getStatusCode());
                return Collections.emptyList();
            }

            return parseResults(response.getBody());
        } catch (Exception e) {
            log.error("[TavilyWebSearch] 搜索异常: query={}", query, e);
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // 结果格式化与辅助方法
    // -------------------------------------------------------------------------

    private String formatResults(List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return ToolResult.ok("未找到相关结果").toJson();
        }
        List<String> sources = results.stream()
                .map(WebSearchResult::url)
                .filter(url -> url != null && !url.isBlank())
                .toList();

        String content = results.stream()
                .map(r -> "- " + r.title() + ": " + r.snippet())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return ToolResult.ok(content, sources, Map.of("total", results.size())).toJson();
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

    private List<WebSearchResult> parseResults(String responseBody) {
        List<WebSearchResult> results = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultsNode = root.path("results");

            if (resultsNode.isArray()) {
                for (JsonNode node : resultsNode) {
                    String title = node.path("title").asText("");
                    String url = node.path("url").asText("");
                    String content = node.path("content").asText("");
                    String snippet = content.length() > 300 ? content.substring(0, 300) + "..." : content;
                    String source = node.path("source").asText("web");

                    results.add(new WebSearchResult(title, url, snippet, content, source));
                }
            }

            log.info("[TavilyWebSearch] 解析结果: count={}", results.size());
        } catch (Exception e) {
            log.error("[TavilyWebSearch] 解析响应失败", e);
        }
        return results;
    }
}
