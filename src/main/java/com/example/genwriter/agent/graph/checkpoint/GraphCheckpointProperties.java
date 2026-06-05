package com.example.genwriter.agent.graph.checkpoint;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * StateGraph Checkpoint 配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "genwriter.checkpoint")
public class GraphCheckpointProperties {

    /**
     * Redis Key 前缀
     */
    private String keyPrefix = "genwriter:graph:checkpoint:";

    /**
     * 是否启用 Checkpoint 持久化
     */
    private boolean enabled = true;

    /**
     * Checkpoint 过期时间（小时）
     */
    private long ttlHours = 24;
}
