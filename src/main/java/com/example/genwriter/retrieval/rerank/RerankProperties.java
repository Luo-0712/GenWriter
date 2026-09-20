package com.example.genwriter.retrieval.rerank;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * cross-encoder 精排服务配置（retrieval.rerank.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.rerank")
public class RerankProperties {

    /**
     * 是否启用 rerank 精排；关闭时使用 NoOpReranker，整条链路回退原排序。
     */
    private boolean enabled = false;

    /**
     * 精排服务 HTTP endpoint，例如 http://127.0.0.1:8100/rerank
     */
    private String endpoint = "";

    /**
     * 精排模型名（随请求体下发）
     */
    private String model = "bge-reranker-v2-m3";

    /**
     * 单次 HTTP 调用连接/读取超时（毫秒）
     */
    private long timeoutMs = 2000;

    /**
     * 批量打分的单批候选上限
     */
    private int batchSize = 32;

    /**
     * 精排后默认保留数量（topN）
     */
    private int topN = 5;

    /**
     * 宽召回候选上限（RAG 侧使用；宽召回不做阈值过滤，阈值判定下沉到 gate）
     */
    private int recallLimit = 30;

    /**
     * 调用失败后的重试次数（0 表示不重试）
     */
    private int maxRetries = 0;
}
