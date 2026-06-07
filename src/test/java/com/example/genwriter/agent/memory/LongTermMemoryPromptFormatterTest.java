package com.example.genwriter.agent.memory;

import com.example.genwriter.model.dto.response.MemoryVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryPromptFormatterTest {

    private final LongTermMemoryPromptFormatter formatter =
            new LongTermMemoryPromptFormatter(new LongTermMemoryMetadataSupport(new ObjectMapper()));

    @Test
    void format_ShouldRenderStableSettingFactsBeforeRegularMemory() {
        MemoryVO memory = MemoryVO.builder()
                .id("m1")
                .memoryType("WORLD_SETTING")
                .scope("PROJECT")
                .importance("HIGH")
                .content(content("API version", "The API version must remain v1."))
                .metadata(Map.of(
                        "title", "API version",
                        "summary", "The API version must remain v1.",
                        "facets", Map.of("name", "API version", "details", "The API version must remain v1."),
                        "identityKey", "world_setting:project:test:apiversion",
                        "updatePolicy", "REPLACE",
                        "source", Map.of("authority", "USER_EXPLICIT"),
                        "conflicts", List.of()
                ))
                .build();

        String output = formatter.format(List.of(memory));

        assertTrue(output.contains("稳定设定事实"));
        assertTrue(output.indexOf("稳定设定事实") < output.lastIndexOf("[API version]"));
        assertTrue(output.contains("The API version must remain v1."));
    }

    @Test
    void format_ShouldNotPromoteMemoryWithPendingConflicts() {
        MemoryVO memory = MemoryVO.builder()
                .id("m2")
                .memoryType("WORLD_SETTING")
                .scope("PROJECT")
                .importance("HIGH")
                .content(content("API version", "The API version must remain v1."))
                .metadata(Map.of(
                        "title", "API version",
                        "summary", "The API version must remain v1.",
                        "facets", Map.of("name", "API version", "details", "The API version must remain v1."),
                        "identityKey", "world_setting:project:test:apiversion",
                        "updatePolicy", "REPLACE",
                        "source", Map.of("authority", "USER_EXPLICIT"),
                        "conflicts", List.of(Map.of("content", "The API version is v2."))
                ))
                .build();

        String output = formatter.format(List.of(memory));

        assertFalse(output.contains("稳定设定事实"));
        assertTrue(output.contains("The API version must remain v1."));
    }

    private String content(String name, String details) {
        return "## Name\n" + name + "\n\n## Details\n" + details;
    }
}
