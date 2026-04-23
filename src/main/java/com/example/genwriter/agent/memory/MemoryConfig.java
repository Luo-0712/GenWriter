package com.example.genwriter.agent.memory;

import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆配置类
 * 集中管理短期记忆相关的常量
 */
@Configuration
public class MemoryConfig {

    /**
     * Advisor 参数中用于传递 conversationId 的键名
     */
    public static final String CONVERSATION_ID_KEY = AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

    /**
     * Advisor 参数中用于传递检索消息数量的键名
     */
    public static final String RETRIEVE_SIZE_KEY = AbstractChatMemoryAdvisor.CHAT_MEMORY_RETRIEVE_SIZE_KEY;
}
