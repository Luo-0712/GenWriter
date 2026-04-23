package com.example.genwriter.agent.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短期记忆配置属性
 * 前缀: genwriter.memory
 */
@Data
@Component
@ConfigurationProperties(prefix = "genwriter.memory")
public class MemoryProperties {

    /**
     * Redis key 前缀
     */
    private String keyPrefix = "genwriter:chat:memory:";

    /**
     * 对话记忆在 Redis 中的保留时长（小时）
     */
    private long ttlHours = 24;

    /**
     * 单次请求从记忆中检索的最大消息数（窗口大小）
     */
    private int windowSize = 20;

    /**
     * Redis 中保存的最大消息数（超出则裁剪）
     */
    private int maxMessages = 50;
}
