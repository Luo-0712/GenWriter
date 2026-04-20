package com.example.genwriter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态 ChatModel
 * 支持运行时切换不同的 LLM 模型（DashScope/通义千问、OpenAI兼容API等）
 * 适配 Spring AI Alibaba
 */
@Slf4j
public class DynamicChatModel implements ChatModel {

    /**
     * 当前激活的模型名称
     */
    private final AtomicReference<String> activeModel = new AtomicReference<>();

    /**
     * 已注册的模型映射
     */
    private final Map<String, ChatModel> modelRegistry = new ConcurrentHashMap<>();

    /**
     * 默认模型名称
     */
    private final String defaultModelName;

    public DynamicChatModel(String defaultModelName) {
        this.defaultModelName = defaultModelName;
        this.activeModel.set(defaultModelName);
        log.info("DynamicChatModel 初始化，默认模型: {}", defaultModelName);
    }

    /**
     * 注册模型
     * @param name 模型名称
     * @param chatModel ChatModel 实例
     */
    public void registerModel(String name, ChatModel chatModel) {
        modelRegistry.put(name, chatModel);
        log.info("注册模型: {}", name);
    }

    /**
     * 切换到指定模型
     * @param modelName 模型名称
     * @return 是否切换成功
     */
    public boolean switchModel(String modelName) {
        if (!modelRegistry.containsKey(modelName)) {
            log.warn("模型不存在，无法切换: {}", modelName);
            return false;
        }
        activeModel.set(modelName);
        log.info("模型已切换: {}", modelName);
        return true;
    }

    /**
     * 获取当前激活的模型名称
     * @return 模型名称
     */
    public String getActiveModel() {
        return activeModel.get();
    }

    /**
     * 获取当前激活的 ChatModel 实例
     * @return ChatModel 实例
     */
    public ChatModel getCurrentModel() {
        ChatModel model = modelRegistry.get(activeModel.get());
        if (model == null) {
            throw new IllegalStateException("当前模型未注册: " + activeModel.get());
        }
        return model;
    }

    /**
     * 获取所有已注册的模型名称
     * @return 模型名称集合
     */
    public java.util.Set<String> getRegisteredModels() {
        return modelRegistry.keySet();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return getCurrentModel().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return getCurrentModel().stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getCurrentModel().getDefaultOptions();
    }
}
