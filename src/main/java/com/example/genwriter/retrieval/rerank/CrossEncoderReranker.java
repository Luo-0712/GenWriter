package com.example.genwriter.retrieval.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * cross-encoder 精排 HTTP 实现（bge-reranker-v2-m3 / Qwen3-Reranker 等）。
 *
 * <p>降级约定：未启用、超时、异常、返回空、解析失败一律返回空列表，
 * 由调用方回退到原融合分排序，绝不阻断主链路。
 */
@Slf4j
@RequiredArgsConstructor
public class CrossEncoderReranker implements Reranker {

    private final RerankProperties properties;
    private final RestTemplateBuilder restTemplateBuilder;
    private final ObjectMapper objectMapper;

    private RestTemplate restTemplate;

    @PostConstruct
    void init() {
        if (properties.getTimeoutMs() > 0) {
            Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
            this.restTemplate = restTemplateBuilder
                    .setConnectTimeout(timeout)
                    .setReadTimeout(timeout)
                    .build();
        }
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled()
                && properties.getEndpoint() != null
                && !properties.getEndpoint().isBlank()
                && restTemplate != null;
    }

    @Override
    public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN) {
        if (!isAvailable() || query == null || query.isBlank()
                || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, Double> scores = new HashMap<>();
            int batchSize = Math.max(1, properties.getBatchSize());
            for (int from = 0; from < candidates.size(); from += batchSize) {
                List<RerankCandidate> batch = candidates.subList(from, Math.min(from + batchSize, candidates.size()));
                scoreBatch(query, batch, scores);
            }
            if (scores.isEmpty()) {
                return List.of();
            }

            List<RerankResult> results = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                RerankCandidate candidate = candidates.get(i);
                Double score = scores.get(candidate.id());
                if (score != null) {
                    results.add(new RerankResult(candidate.id(), score, i));
                }
            }
            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            if (topN > 0 && results.size() > topN) {
                results = new ArrayList<>(results.subList(0, topN));
            }
            return results;
        } catch (Exception e) {
            // 降级：返回空触发调用方回退原排序，warn 级别避免刷屏
            log.warn("[Rerank] 精排调用失败，降级为原排序: {}", e.getMessage());
            return List.of();
        }
    }

    private void scoreBatch(String query, List<RerankCandidate> batch, Map<String, Double> scores) throws Exception {
        List<String> documents = batch.stream().map(RerankCandidate::text).toList();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("query", query);
        body.put("documents", documents);

        int attempts = Math.max(0, properties.getMaxRetries()) + 1;
        ResponseEntity<String> response = null;
        Exception lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                response = restTemplate.postForEntity(properties.getEndpoint(),
                        new HttpEntity<>(body, headers), String.class);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (lastError != null || response == null || !response.getStatusCode().is2xxSuccessful()
                || response.getBody() == null) {
            throw new IllegalStateException("rerank 服务调用失败: "
                    + (lastError != null ? lastError.getMessage()
                    : response != null ? response.getStatusCode() : "empty response"));
        }

        parseScores(response.getBody(), batch, scores);
    }

    /**
     * 兼容两种常见返回格式：
     * <pre>
     * {"results":[{"index":0,"score":0.98}, ...]}   // Jina / TEI 风格
     * [[0.98, 0.02], ...]                            // 按文档序号的分数数组
     * </pre>
     */
    private void parseScores(String responseBody, List<RerankCandidate> batch, Map<String, Double> scores) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resultsNode = root.path("results");
            if (resultsNode.isArray() && resultsNode.size() > 0) {
                for (JsonNode node : resultsNode) {
                    int index = node.path("index").asInt(-1);
                    double score = node.path("score").asDouble(Double.NaN);
                    if (index >= 0 && index < batch.size() && !Double.isNaN(score)) {
                        scores.put(batch.get(index).id(), score);
                    }
                }
                return;
            }
            if (root.isArray() && root.size() == batch.size()) {
                for (int i = 0; i < batch.size(); i++) {
                    JsonNode node = root.get(i);
                    double score = node.isArray() ? node.get(0).asDouble(Double.NaN)
                            : node.asDouble(Double.NaN);
                    if (!Double.isNaN(score)) {
                        scores.put(batch.get(i).id(), score);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("rerank 响应解析失败: " + e.getMessage(), e);
        }
    }
}
