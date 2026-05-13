package com.example.genwriter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词管理器
 * 从 classpath:prompts/{lang}/*.yaml 加载外部提示词文件，支持热重载和语言降级。
 * <p>
 * 优先级链：application.yml (LLMConfig) > 外部 YAML (PromptManager) > Java 内联默认值
 */
@Slf4j
@Component
public class PromptManager {

    private final Map<String, String> prompts = new ConcurrentHashMap<>();
    private String currentLanguage = "zh";

    @PostConstruct
    public void init() {
        loadPrompts(currentLanguage);
    }

    /**
     * 获取提示词，未找到返回 null
     */
    public String getPrompt(String key) {
        return prompts.get(key);
    }

    /**
     * 获取提示词，未找到返回 fallback
     */
    public String getPrompt(String key, String fallback) {
        return prompts.getOrDefault(key, fallback);
    }

    /**
     * 热重载：重新从文件系统加载所有提示词
     */
    public synchronized void reloadPrompts() {
        prompts.clear();
        loadPrompts(currentLanguage);
        log.info("[PromptManager] 提示词已重载, language={}, count={}", currentLanguage, prompts.size());
    }

    /**
     * 切换语言并重载
     */
    public synchronized void switchLanguage(String language) {
        this.currentLanguage = language;
        reloadPrompts();
    }

    /**
     * 获取所有已加载的提示词 key
     */
    public Set<String> getLoadedKeys() {
        return Collections.unmodifiableSet(prompts.keySet());
    }

    // -------------------------------------------------------------------------
    // 内部加载逻辑
    // -------------------------------------------------------------------------

    private void loadPrompts(String language) {
        int loaded = loadFromDirectory("prompts/" + language);
        if (loaded == 0) {
            log.warn("[PromptManager] 未找到语言 '{}' 的提示词文件，尝试 'en'", language);
            loaded = loadFromDirectory("prompts/en");
        }
        if (loaded == 0) {
            log.warn("[PromptManager] 未找到 'en' 提示词文件，尝试扫描所有可用文件");
            loaded = loadAnyAvailable();
        }
        log.info("[PromptManager] 已加载 {} 个提示词, language={}", loaded, language);
    }

    private int loadFromDirectory(String classpathDir) {
        int count = 0;
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath:" + classpathDir + "/*.yaml");

            for (var res : resources) {
                try (InputStream is = res.getInputStream()) {
                    count += loadYamlStream(is);
                    log.debug("[PromptManager] 已加载: {}", res.getFilename());
                } catch (Exception e) {
                    log.error("[PromptManager] 加载失败: {}", res.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.debug("[PromptManager] 目录不存在: {}", classpathDir);
        }
        return count;
    }

    private int loadAnyAvailable() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath:prompts/**/*.yaml");
            int count = 0;
            for (var res : resources) {
                try (InputStream is = res.getInputStream()) {
                    count += loadYamlStream(is);
                } catch (Exception e) {
                    log.error("[PromptManager] 加载失败: {}", res.getFilename(), e);
                }
            }
            return count;
        } catch (Exception e) {
            log.warn("[PromptManager] 未找到任何提示词文件");
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private int loadYamlStream(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(is);
        if (data == null) return 0;

        int count = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (entry.getValue() instanceof String value) {
                prompts.put(entry.getKey(), value.strip());
                count++;
            }
        }
        return count;
    }
}
