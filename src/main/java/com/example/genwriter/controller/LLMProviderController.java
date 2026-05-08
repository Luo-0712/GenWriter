package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.service.LLMProviderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LLMProviderController {

    private final LLMProviderService llmProviderService;

    /**
     * 获取所有已配置供应商
     */
    @GetMapping("/providers")
    public ApiResponse<List<Map<String, Object>>> getAllProviders() {
        return ApiResponse.success(llmProviderService.getAllProviders());
    }

    /**
     * 获取当前激活模型信息
     */
    @GetMapping("/active-model")
    public ApiResponse<Map<String, Object>> getActiveModel() {
        return ApiResponse.success(llmProviderService.getActiveModelInfo());
    }

    /**
     * 切换激活模型
     */
    @PostMapping("/switch-model")
    public ApiResponse<Map<String, Object>> switchModel(@RequestBody Map<String, String> request) {
        String providerType = request.get("providerType");
        String modelName = request.get("modelName");
        if (providerType == null || modelName == null) {
            return ApiResponse.error("400", "providerType 和 modelName 不能为空");
        }
        llmProviderService.switchModel(providerType, modelName);
        return ApiResponse.success("模型已切换", llmProviderService.getActiveModelInfo());
    }

    /**
     * 测试供应商连通性
     */
    @PostMapping("/providers/{type}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable String type) {
        return ApiResponse.success(llmProviderService.testConnection(type));
    }
}
