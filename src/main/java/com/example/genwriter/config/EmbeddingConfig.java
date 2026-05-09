package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 嵌入模型配置
 * 集中管理文本向量化相关的参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.embedding")
public class EmbeddingConfig {

    /**
     * 默认嵌入模型标识
     */
    private String defaultModel = "text-embedding-v4";

    /**
     * 向量维度，需与数据库表定义一致
     */
    private int dimension = 1024;

    /**
     * 批量嵌入的最大文本数量
     */
    private int batchSize = 100;

    /**
     * 相似度搜索默认返回数量
     */
    private int defaultSearchLimit = 5;

    /**
     * 相似度阈值 (0.0 - 1.0)，低于此值的结果被过滤
     */
    private double similarityThreshold = 0.7;

    /**
     * 是否使用数据库级向量搜索 (pgvector)
     * 设为 false 时回退到内存计算
     */
    private boolean useDbVectorSearch = true;
}
