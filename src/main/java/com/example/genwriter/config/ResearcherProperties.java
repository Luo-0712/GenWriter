package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 研究调研配置属性
 * 前缀: genwriter.researcher
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.researcher")
public class ResearcherProperties {

    /**
     * 单次调研最大搜索查询数
     */
    private int maxSearchQueries = 5;

    /**
     * 每个查询返回的最大结果数
     */
    private int maxSearchResultsPerQuery = 5;

    /**
     * 验证不通过时的最大补充搜索轮数
     */
    private int maxVerificationLoops = 2;

    /**
     * 是否启用 researcher worker
     */
    private boolean enabled = true;
}
