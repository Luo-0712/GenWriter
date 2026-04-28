package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 网页搜索配置属性
 * 前缀: genwriter.websearch
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "genwriter.websearch")
public class WebSearchProperties {

    /**
     * API密钥（Tavily等）
     */
    private String apiKey = "";

    /**
     * 请求超时时间（秒）
     */
    private int timeoutSeconds = 30;
}
