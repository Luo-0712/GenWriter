package com.example.genwriter.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 真实环境测试配置
 * 启用所有 AI 自动配置，使用真实 API Key
 */
@TestConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.example.genwriter")
public class RealEnvironmentTestConfig {
}
