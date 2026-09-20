package com.example.genwriter.retrieval.rerank;

import java.util.List;

/**
 * 精排接口：长期记忆侧与 RAG 侧共用。
 *
 * <p>实现要求（降级优先于精度）：
 * <ul>
 *   <li>未启用 / 超时 / 异常 / 返回空时返回空列表，由调用方回退到原排序，绝不抛异常中断检索主链路。</li>
 *   <li>批量提交候选，单次 HTTP 传全部候选（内部按 batchSize 分批）。</li>
 * </ul>
 */
public interface Reranker {

    /**
     * 精排服务当前是否可用（已启用且配置了 endpoint）。
     */
    boolean isAvailable();

    /**
     * 对候选做 cross-encoder 精排。
     *
     * @param query      检索 query
     * @param candidates 候选列表
     * @param topN       精排后保留的数量（&lt;=0 表示不截断）
     * @return 按 rerank 分数降序的结果；服务不可用或调用失败时返回空列表
     */
    List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN);
}
