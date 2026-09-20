package com.example.genwriter.retrieval.rerank;

/**
 * 精排结果。
 *
 * @param id          候选唯一标识
 * @param score       cross-encoder 判别式分数（通常 0-1）
 * @param originalRank 精排前该候选在融合排序中的名次（0 起），用于降级与埋点
 */
public record RerankResult(String id, double score, int originalRank) {
}
