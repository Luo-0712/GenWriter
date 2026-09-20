package com.example.genwriter.retrieval.gate;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局门控配置（retrieval.gate.*），长期记忆侧与 RAG 侧共用。
 */
@Data
@Component
@ConfigurationProperties(prefix = "retrieval.gate")
public class RetrievalGateProperties {

    /**
     * 是否启用相关性闸门；关闭时 evaluate 恒通过（保留决策记录便于对比）
     */
    private boolean enabled = true;

    /**
     * top1 绝对分下限（rerank 判别式分数或融合分语义，按开启方校准）
     */
    private double minTopScore = 0.40;

    /**
     * top1 与第 k 名的最小间隔
     */
    private double minMargin = 0.05;

    /**
     * 相对间隔取第几名（1-based）
     */
    private int marginIndex = 3;

    /**
     * 融合分统一下限过滤（非 rerank 降级路径使用），低于该分的候选直接剔除
     */
    private double minScoreFloor = 0.25;
}
