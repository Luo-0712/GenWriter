package com.example.genwriter.service;

import com.example.genwriter.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.Embedding;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 嵌入服务，用于生成文本向量
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * 生成单个文本的嵌入向量
     */
    public float[] embed(String text) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(List.of(text), null);
            EmbeddingResponse response = embeddingModel.call(request);
            return response.getResults().get(0).getOutput();
        } catch (Exception e) {
            log.error("生成嵌入失败: text={}, error={}", text, e.getMessage(), e);
            throw new RuntimeException("生成嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成文本的嵌入向量
     */
    public List<float[]> embed(List<String> texts) {
        try {
            EmbeddingRequest request = new EmbeddingRequest(texts, null);
            EmbeddingResponse response = embeddingModel.call(request);
            return response.getResults().stream()
                    .map(Embedding::getOutput)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("批量生成嵌入失败: error={}", e.getMessage(), e);
            throw new RuntimeException("批量生成嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算两个文本的相似度
     */
    public double calculateSimilarity(String textA, String textB) {
        float[] embeddingA = embed(textA);
        float[] embeddingB = embed(textB);
        return VectorUtils.cosineSimilarity(embeddingA, embeddingB);
    }

    /**
     * 寻找与最相似的文本
     */
    public SearchResult findMostSimilar(String query, List<String> candidates) {
        float[] queryEmbedding = embed(query);
        List<float[]> candidateEmbeddings = candidates.stream()
                .map(this::embed)
                .collect(Collectors.toList());

        float[] similarities = VectorUtils.computeSimilarities(queryEmbedding, candidateEmbeddings);

        int maxIndex = 0;
        double maxSimilarity = similarities[0];

        for (int i = 1; i < similarities.length; i++) {
            if (similarities[i] > maxSimilarity) {
                maxSimilarity = similarities[i];
                maxIndex = i;
            }
        }

        return new SearchResult(candidates.get(maxIndex), maxSimilarity, similarities);
    }

    /**
     * 搜索结果
     */
    public record SearchResult(String text, double similarity, float[] allSimilarities) {}
}