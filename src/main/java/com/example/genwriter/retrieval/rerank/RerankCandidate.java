package com.example.genwriter.retrieval.rerank;

/**
 * 精排通用候选结构：屏蔽记忆实体与知识片段实体的类型差异。
 *
 * @param id   候选唯一标识（记忆 id / 知识片段 id）
 * @param text 送入 cross-encoder 的文本（不落日志）
 */
public record RerankCandidate(String id, String text) {
}
