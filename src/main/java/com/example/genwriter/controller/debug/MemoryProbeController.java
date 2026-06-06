package com.example.genwriter.controller.debug;

import com.example.genwriter.model.common.ApiResponse;
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

    @GetMapping("/{sessionId}/settings")
    public ApiResponse<Map<String, Object>> settingStats(@PathVariable String sessionId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0;

        for (String type : SETTING_TYPES) {
            long count = memoryService.listByType(MemoryType.valueOf(type)).stream()
                    .filter(memory -> sessionId.equals(memory.getSessionId()))
                    .count();
            byType.put(type, count);
            total += count;
        }

        return ApiResponse.success(Map.of(
                "sessionId", sessionId,
                "total", total,
                "byType", byType
        ));
    }
}
