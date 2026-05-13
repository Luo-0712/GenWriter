package com.example.genwriter.controller;

import com.example.genwriter.config.PromptManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 提示词管理 API
 * 支持热重载外部 YAML 提示词文件和查看已加载的 key 列表。
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptManager promptManager;

    /**
     * 热重载：重新从 classpath 加载所有外部 YAML 提示词
     */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        promptManager.reloadPrompts();
        return Map.of(
                "success", true,
                "loadedKeys", promptManager.getLoadedKeys().size()
        );
    }

    /**
     * 查看已加载的所有提示词 key
     */
    @GetMapping("/keys")
    public Set<String> keys() {
        return promptManager.getLoadedKeys();
    }
}
