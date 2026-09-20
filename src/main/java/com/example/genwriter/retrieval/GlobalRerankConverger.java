package com.example.genwriter.retrieval;

import com.example.genwriter.retrieval.gate.GateDecision;
import com.example.genwriter.retrieval.gate.RelevanceGate;
import com.example.genwriter.retrieval.rerank.RerankCandidate;
import com.example.genwriter.retrieval.rerank.RerankResult;
import com.example.genwriter.retrieval.rerank.Reranker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多 query 全局收敛器：对多个检索 query 的合并候选做全局重排与门控。
 *
 * <p>跨 query 精排策略：每个 query 精排一次，取每个候选的跨 query 最高分，
 * 避免逐 query 注入导致的注入量放大（N 个 query × topK）。
 */
@Component
@RequiredArgsConstructor
public class GlobalRerankConverger {

    private final Reranker reranker;
    private final RelevanceGate relevanceGate;

    public boolean isRerankAvailable() {
        return reranker.isAvailable();
    }

    /**
     * 全局重排：合并候选按跨 query 最高 rerank 分排序并截断 topN。
     *
     * @return 按 rerank 分降序的结果；rerank 不可用时返回空列表（调用方回退相似度排序）
     */
    public List<RerankResult> globalRerank(List<String> queries,
                                           List<RerankCandidate> candidates,
                                           int topN) {
        if (queries == null || queries.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, Double> bestScore = new HashMap<>();
        Map<String, Integer> bestOriginalRank = new HashMap<>();
        for (String query : queries) {
            if (query == null || query.isBlank()) {
                continue;
            }
            List<RerankResult> results = reranker.rerank(query, candidates, 0);
            for (RerankResult result : results) {
                Double current = bestScore.get(result.id());
                if (current == null || result.score() > current) {
                    bestScore.put(result.id(), result.score());
                    bestOriginalRank.put(result.id(), result.originalRank());
                }
            }
        }
        if (bestScore.isEmpty()) {
            return List.of();
        }

        List<RerankResult> merged = new ArrayList<>();
        for (RerankCandidate candidate : candidates) {
            Double score = bestScore.get(candidate.id());
            if (score != null) {
                merged.add(new RerankResult(candidate.id(), score,
                        bestOriginalRank.getOrDefault(candidate.id(), 0)));
            }
        }
        merged.sort(Comparator.comparingDouble(RerankResult::score).reversed());
        if (topN > 0 && merged.size() > topN) {
            return new ArrayList<>(merged.subList(0, topN));
        }
        return merged;
    }

    /**
     * 对全局排序后的分数做统一门控。
     */
    public GateDecision gate(List<Double> sortedScoresDesc) {
        return relevanceGate.evaluate(sortedScoresDesc);
    }
}
