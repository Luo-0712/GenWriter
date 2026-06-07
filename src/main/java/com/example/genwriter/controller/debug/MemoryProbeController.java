package com.example.genwriter.controller.debug;

import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Profile({"debug", "test"})
@RequestMapping("/api/debug/memory-probe")
@RequiredArgsConstructor
public class MemoryProbeController {

    private static final List<String> SETTING_TYPES = List.of(
            "WORLD_SETTING",
            "CHARACTER_PROFILE",
            "FORESHADOWING"
    );

    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProbeRecorder probeRecorder;

    @GetMapping("/{sessionId}/settings")
    public ApiResponse<Map<String, Object>> settingStats(@PathVariable String sessionId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        List<Map<String, Object>> memories = new java.util.ArrayList<>();
        long total = 0;

        for (String type : SETTING_TYPES) {
            List<MemoryVO> scoped = memoryService.listByType(MemoryType.valueOf(type)).stream()
                    .filter(memory -> sessionId.equals(memory.getSessionId()))
                    .toList();
            long count = scoped.size();
            byType.put(type, count);
            total += count;
            scoped.stream().map(this::toProbeMemory).forEach(memories::add);
        }

        return ApiResponse.success(Map.of(
                "sessionId", sessionId,
                "total", total,
                "byType", byType,
                "memories", memories,
                "recentEvents", probeRecorder.recentEvents(sessionId)
        ));
    }

    private Map<String, Object> toProbeMemory(MemoryVO memory) {
        Map<String, Object> metadata = memory.getMetadata() instanceof Map<?, ?> map
                ? toStringMap(map)
                : Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", safe(memory.getId()));
        result.put("memoryType", safe(memory.getMemoryType()));
        result.put("title", safe(metadata.get("title")));
        result.put("identityKey", safe(metadata.get("identityKey")));
        result.put("source", metadata.getOrDefault("source", Map.of()));
        result.put("updatePolicy", metadata.getOrDefault("updatePolicy", ""));
        result.put("memoryVersion", metadata.getOrDefault("memoryVersion", 1));
        result.put("conflicts", metadata.getOrDefault("conflicts", List.of()));
        result.put("versions", metadata.getOrDefault("versions", List.of()));
        result.put("importance", safe(memory.getImportance()));
        result.put("updatedAt", memory.getUpdatedAt() != null ? memory.getUpdatedAt().toString() : "");
        return result;
    }

    private Map<String, Object> toStringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private String safe(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
