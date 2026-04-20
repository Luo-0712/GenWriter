package com.example.genwriter.config;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * 测试配置类
 * 排除需要真实API连接的自动配置，使用Mock替代
 */
@TestConfiguration
@EnableAutoConfiguration(exclude = {
    DashScopeAutoConfiguration.class
})
@ComponentScan(basePackages = "com.example.genwriter")
public class TestConfig {

    /**
     * Mock ChatModel Bean
     * 用于测试中替代真实的Spring AI Alibaba ChatModel
     */
    @Bean
    @Primary
    public ChatModel chatModel() {
        return mock(ChatModel.class);
    }

    /**
     * Mock DashScope ChatModel Bean
     */
    @Bean("dashScopeChatModel")
    public ChatModel dashScopeChatModel() {
        return mock(ChatModel.class);
    }
}