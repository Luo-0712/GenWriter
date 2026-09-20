package com.example.genwriter.retrieval.rerank;

import java.util.List;

/**
 * 空实现：未启用或精排服务不可用时的默认 Bean，保证主链路不阻断。
 */
public class NoOpReranker implements Reranker {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, int topN) {
        return List.of();
    }
}
