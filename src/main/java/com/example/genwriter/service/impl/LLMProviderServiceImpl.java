package com.example.genwriter.service.impl;

import com.example.genwriter.config.ChatModelFactory;
import com.example.genwriter.config.DynamicChatModel;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.service.LLMProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMProviderServiceImpl implements LLMProviderService {

    private final LLMConfig llmConfig;
    private final DynamicChatModel dynamicChatModel;
    private final ChatModelFactory chatModelFactory;

    @Override
    public List<Map<String, Object>> getAllProviders() {
        String activeModelKey = dynamicChatModel.getActiveModel();
        List<Map<String, Object>> result = new ArrayList<>();

        for (LLMConfig.ProviderConfig provider : llmConfig.getProviders()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", provider.getType());
            item.put("displayName", provider.getDisplayName());
            item.put("apiKey", maskApiKey(provider.getApiKey()));
            item.put("baseUrl", provider.getBaseUrl());
            item.put("apiCompatibility", provider.getApiCompatibility());
            item.put("models", provider.getModels());
            item.put("activeModel", provider.getActiveModel());

            String providerKey = provider.getType() + ":" + provider.getActiveModel();
            item.put("isActive", providerKey.equals(activeModelKey));

            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> getActiveModelInfo() {
        String activeKey = dynamicChatModel.getActiveModel();
        String[] parts = activeKey.split(":", 2);
        String providerType = parts.length > 0 ? parts[0] : "unknown";
        String modelName = parts.length > 1 ? parts[1] : activeKey;

        // 查找显示名称
        String displayName = providerType;
        for (LLMConfig.ProviderConfig p : llmConfig.getProviders()) {
            if (p.getType().equals(providerType)) {
                displayName = p.getDisplayName();
                break;
            }
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("providerType", providerType);
        info.put("displayName", displayName);
        info.put("modelName", modelName);
        info.put("fullKey", activeKey);
        return info;
    }

    @Override
    public void switchModel(String providerType, String modelName) {
        // 校验供应商存在
        LLMConfig.ProviderConfig provider = llmConfig.getProviders().stream()
                .filter(p -> p.getType().equals(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在: " + providerType));

        // 校验模型存在于该供应商
        if (!provider.getModels().contains(modelName)) {
            throw new IllegalArgumentException(
                    "模型 " + modelName + " 不在供应商 " + providerType + " 的模型列表中");
        }

        String key = providerType + ":" + modelName;

        // 如果模型未注册（用户在 YAML 新增了模型），先注册
        if (!dynamicChatModel.getRegisteredModels().contains(key)) {
            try {
                ChatModel model = chatModelFactory.createChatModel(provider);
                dynamicChatModel.registerModel(key, model);
                log.info("动态注册模型: {}", key);
            } catch (Exception e) {
                throw new RuntimeException("注册模型失败: " + e.getMessage(), e);
            }
        }

        boolean switched = dynamicChatModel.switchModel(key);
        if (!switched) {
            throw new RuntimeException("切换模型失败: " + key);
        }
        log.info("模型已切换: {}", key);
    }

    @Override
    public Map<String, Object> testConnection(String providerType) {
        LLMConfig.ProviderConfig provider = llmConfig.getProviders().stream()
                .filter(p -> p.getType().equals(providerType))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在: " + providerType));

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            ChatModel model = chatModelFactory.createChatModel(provider);
            String testKey = "__test__:" + providerType;
            dynamicChatModel.registerModel(testKey, model);

            // 使用简单 prompt 测试
            String previousModel = dynamicChatModel.getActiveModel();
            dynamicChatModel.switchModel(testKey);
            try {
                var response = dynamicChatModel.call(new Prompt("Hi, reply with 'OK' only."));
                String content = response.getResult().getOutput().getText();
                result.put("success", true);
                result.put("message", "连接成功");
                result.put("response", content != null ? content.trim() : "");
            } finally {
                dynamicChatModel.switchModel(previousModel);
                dynamicChatModel.unregisterModel(testKey);
            }
        } catch (Exception e) {
            log.warn("测试连接失败 [{}]: {}", providerType, e.getMessage());
            result.put("success", false);
            result.put("message", "连接失败: " + e.getMessage());
        }
        return result;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - 4);
    }
}
