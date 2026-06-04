package com.example.genwriter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Component
@ConfigurationProperties(prefix = "genwriter.llm")
public class ModelCapabilityConfig {

    private List<ModelCapability> models = new ArrayList<>();

    @Data
    public static class ModelCapability {
        private String name;
        private boolean supportsVision;
    }

    private final Map<String, Boolean> visionCache = new ConcurrentHashMap<>();

    public boolean supportsVision(String modelName) {
        return visionCache.computeIfAbsent(modelName, name ->
            models.stream()
                .filter(m -> name.equals(m.getName()))
                .findFirst()
                .map(ModelCapability::isSupportsVision)
                .orElse(false)
        );
    }
}
