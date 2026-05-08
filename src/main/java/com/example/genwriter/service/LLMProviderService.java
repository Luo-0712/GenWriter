package com.example.genwriter.service;

import java.util.List;
import java.util.Map;

/**
 * LLM 供应商管理服务
 */
public interface LLMProviderService {

    /**
     * 获取所有已配置供应商（apiKey 脱敏）
     */
    List<Map<String, Object>> getAllProviders();

    /**
     * 获取当前激活的模型信息
     */
    Map<String, Object> getActiveModelInfo();

    /**
     * 切换激活模型
     * @param providerType 供应商类型
     * @param modelName 模型名称
     */
    void switchModel(String providerType, String modelName);

    /**
     * 测试供应商连通性
     * @param providerType 供应商类型
     * @return 测试结果
     */
    Map<String, Object> testConnection(String providerType);
}
