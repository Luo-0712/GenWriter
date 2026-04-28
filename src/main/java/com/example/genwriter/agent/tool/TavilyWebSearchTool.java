package com.example.genwriter.agent.tool;

import com.example.genwriter.config.WebSearchProperties;
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

/**
 * Tavily 网页搜索工具实现
 * 调用 Tavily Search API (https://api.tavily.com/search)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavilyWebSearchTool implements WebSearchTool {

    private static final String TAVILY_API_URL = "https://api.tavily.com/search";

    private final WebSearchProperties webSearchProperties;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(webSearchProperties.getTimeoutSeconds()))
                .setReadTimeout(Duration.ofSeconds(webSearchProperties.getTimeoutSeconds()))
                .build();
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
