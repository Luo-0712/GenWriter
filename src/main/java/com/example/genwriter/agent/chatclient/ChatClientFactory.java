package com.example.genwriter.agent.chatclient;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.example.genwriter.config.DynamicChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * ChatClient 工厂类
 * 负责创建不同配置的 ChatClient 实例
 * 适配 Spring AI Alibaba
 */
@Slf4j
@Component
public class ChatClientFactory {

    private final DynamicChatModel dynamicChatModel;

    public ChatClientFactory(DynamicChatModel dynamicChatModel) {
        this.dynamicChatModel = dynamicChatModel;
    }

    /**
     * 创建默认 ChatClient（使用当前激活的模型）
     * @return ChatClient 实例
     */
    public ChatClient createDefault() {
        return ChatClient.builder(dynamicChatModel).build();
    }

    /**
     * 创建带系统提示词的 ChatClient
     * @param systemPrompt 系统提示词
     * @return ChatClient 实例
     */
    public ChatClient createWithSystemPrompt(String systemPrompt) {
        return ChatClient.builder(dynamicChatModel)
                .defaultSystem(systemPrompt)
                .build();
    }

    /**
     * 创建指定模型和系统提示词的 ChatClient
     * @param modelName 模型名称
     * @param systemPrompt 系统提示词
     * @return ChatClient 实例
     */
    public ChatClient create(String modelName, String systemPrompt) {
        dynamicChatModel.switchModel(modelName);
        ChatClient.Builder builder = ChatClient.builder(dynamicChatModel);
        if (systemPrompt != null) {
            builder.defaultSystem(systemPrompt);
        }
        return builder.build();
    }

    /**
     * 获取当前激活的模型名称
     * @return 模型名称
     */
    public String getActiveModel() {
        return dynamicChatModel.getActiveModel();
    }

    /**
     * 切换模型
     * @param modelName 模型名称
     * @return 是否切换成功
     */
    public boolean switchModel(String modelName) {
        return dynamicChatModel.switchModel(modelName);
    }

    /**
     * 创建指定 temperature 的 ChatClient，用于不同节点的差异化参数
     */
    public ChatClient create(double temperature) {
        return ChatClient.builder(dynamicChatModel)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withTemperature(temperature)
                        .build())
                .build();
    }
}
