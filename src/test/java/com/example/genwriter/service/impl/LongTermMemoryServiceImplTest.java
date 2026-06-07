package com.example.genwriter.service.impl;

import com.example.genwriter.agent.memory.LongTermMemoryMetadataSupport;
import com.example.genwriter.agent.memory.LongTermMemoryProbeRecorder;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.mapper.LongTermMemoryMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LongTermMemoryServiceImplTest {

    @Mock
    private LongTermMemoryMapper mapper;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private TaskSessionMapper taskSessionMapper;

    @Mock
    private LongTermMemoryProbeRecorder probeRecorder;

    private LongTermMemoryMetadataSupport metadataSupport;
    private LongTermMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        metadataSupport = new LongTermMemoryMetadataSupport(new ObjectMapper());
        service = new LongTermMemoryServiceImpl(
                mapper,
                embeddingService,
                taskSessionMapper,
                new LongTermMemoryProperties(),
                metadataSupport,
                probeRecorder
        );
        when(embeddingService.embed(anyString())).thenReturn(new float[]{1.0f, 0.0f});
    }

    @Test
    void storeMemory_ShouldCreateWhenIdentityDoesNotExist() {
        service.storeMemory(content("Lunar relay", "Latency must pass through the lunar relay."),
                MemoryType.WORLD_SETTING, "PROJECT", projectId(), sessionId(), "HIGH",
                metadata("Lunar relay", "Latency must pass through the lunar relay.", "USER_EXPLICIT", "REPLACE"));

        ArgumentCaptor<LongTermMemory> memoryCaptor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(mapper).insert(memoryCaptor.capture());
        LongTermMemory inserted = memoryCaptor.getValue();
        Map<String, Object> metadata = metadataSupport.parseMetadata(inserted.getMetadata());
        assertTrue(String.valueOf(metadata.get("identityKey")).contains("lunarrelay"));
        assertEquals("REPLACE", metadata.get("updatePolicy"));
        assertEquals(1, metadata.get("memoryVersion"));
        assertEquals("USER_EXPLICIT", metadataSupport.toMap(metadata.get("source")).get("authority"));
        verify(probeRecorder).recordWriteDecision(eq(sessionId()), eq("create"), isNull(),
                eq("WORLD_SETTING"), contains("lunarrelay"), eq("USER_EXPLICIT"), eq("REPLACE"), eq(1));
    }

    @Test
    void storeMemory_ShouldReplaceModelFactWhenUserExplicitUsesSameIdentity() {
        LongTermMemory existing = memory("m1", "Platform name", "The platform is called DraftForge.",
                "MODEL_EXTRACTED", "MERGE");
        when(mapper.findByIdentityKey(eq("WORLD_SETTING"), eq("PROJECT"), eq(projectId()), anyString()))
                .thenReturn(existing);

        service.storeMemory(content("Platform name", "The platform name must remain GenWriter."),
                MemoryType.WORLD_SETTING, "PROJECT", projectId(), sessionId(), "HIGH",
                metadata("Platform name", "The platform name must remain GenWriter.", "USER_EXPLICIT", "REPLACE"));

        ArgumentCaptor<LongTermMemory> updateCaptor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(mapper).updateById(updateCaptor.capture());
        LongTermMemory updated = updateCaptor.getValue();
        Map<String, Object> updatedMetadata = metadataSupport.parseMetadata(updated.getMetadata());
        assertEquals(content("Platform name", "The platform name must remain GenWriter."), updated.getContent());
        assertEquals(2, updatedMetadata.get("memoryVersion"));
        assertFalse(metadataSupport.toStringList(updatedMetadata.get("versions")).isEmpty());
        assertEquals("USER_EXPLICIT", metadataSupport.toMap(updatedMetadata.get("source")).get("authority"));
    }

    @Test
    void storeMemory_ShouldMergeModelFactsWithSameIdentity() {
        LongTermMemory existing = memory("m2", "Moon port", "The moon port closes at dusk.",
                "MODEL_EXTRACTED", "MERGE");
        when(mapper.findByIdentityKey(eq("WORLD_SETTING"), eq("PROJECT"), eq(projectId()), anyString()))
                .thenReturn(existing);

        service.storeMemory(content("Moon port", "Cargo ships must dock before sunset."),
                MemoryType.WORLD_SETTING, "PROJECT", projectId(), sessionId(), "MEDIUM",
                metadata("Moon port", "Cargo ships must dock before sunset.", "MODEL_EXTRACTED", "MERGE"));

        ArgumentCaptor<LongTermMemory> updateCaptor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(mapper).updateById(updateCaptor.capture());
        LongTermMemory updated = updateCaptor.getValue();
        assertTrue(updated.getContent().contains("The moon port closes at dusk."));
        assertTrue(updated.getContent().contains("Cargo ships must dock before sunset."));
    }

    @Test
    void storeMemory_ShouldRecordConflictWhenModelContradictsUserExplicitFact() {
        LongTermMemory existing = memory("m3", "API version", "The API version must remain v1.",
                "USER_EXPLICIT", "REPLACE");
        when(mapper.findByIdentityKey(eq("WORLD_SETTING"), eq("PROJECT"), eq(projectId()), anyString()))
                .thenReturn(existing);

        service.storeMemory(content("API version", "The API version is v2."),
                MemoryType.WORLD_SETTING, "PROJECT", projectId(), sessionId(), "MEDIUM",
                metadata("API version", "The API version is v2.", "MODEL_EXTRACTED", "MERGE"));

        ArgumentCaptor<LongTermMemory> updateCaptor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(mapper).updateById(updateCaptor.capture());
        LongTermMemory updated = updateCaptor.getValue();
        Map<String, Object> updatedMetadata = metadataSupport.parseMetadata(updated.getMetadata());
        assertEquals(existing.getContent(), updated.getContent());
        assertFalse(((List<?>) updatedMetadata.get("conflicts")).isEmpty());
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(LongTermMemory.class));
    }

    @Test
    void storeMemory_ShouldResolveLegacyMemoryWithoutIdentityKeyByName() {
        LongTermMemory existing = legacyMemory("m4", "Station name", "The station is called Aurora.",
                "MODEL_EXTRACTED", "MERGE");
        when(mapper.selectByFilter(eq("WORLD_SETTING"), eq("PROJECT"), eq(projectId()),
                eq(null), eq("Station name"), eq(20), eq(0)))
                .thenReturn(List.of(existing));

        service.storeMemory(content("Station name", "The station name must remain Helios."),
                MemoryType.WORLD_SETTING, "PROJECT", projectId(), sessionId(), "HIGH",
                metadata("Station name", "The station name must remain Helios.", "USER_EXPLICIT", "REPLACE"));

        ArgumentCaptor<LongTermMemory> updateCaptor = ArgumentCaptor.forClass(LongTermMemory.class);
        verify(mapper).updateById(updateCaptor.capture());
        LongTermMemory updated = updateCaptor.getValue();
        Map<String, Object> updatedMetadata = metadataSupport.parseMetadata(updated.getMetadata());
        assertEquals(content("Station name", "The station name must remain Helios."), updated.getContent());
        assertTrue(String.valueOf(updatedMetadata.get("identityKey")).contains("stationname"));
    }

    private LongTermMemory memory(String id, String name, String details, String authority, String updatePolicy) {
        String content = content(name, details);
        Map<String, Object> normalized = metadataSupport.normalize(content, "WORLD_SETTING",
                "PROJECT", projectId(), sessionId(), "HIGH", metadata(name, details, authority, updatePolicy));
        return LongTermMemory.builder()
                .id(id)
                .content(content)
                .memoryType("WORLD_SETTING")
                .scope("PROJECT")
                .projectId(projectId())
                .sessionId(sessionId())
                .importance("HIGH")
                .embeddingModel("text-embedding-v4")
                .metadata(metadataSupport.toJson(normalized))
                .accessCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private LongTermMemory legacyMemory(String id, String name, String details, String authority, String updatePolicy) {
        Map<String, Object> legacyMetadata = metadata(name, details, authority, updatePolicy);
        legacyMetadata.remove("updatePolicy");
        return LongTermMemory.builder()
                .id(id)
                .content(content(name, details))
                .memoryType("WORLD_SETTING")
                .scope("PROJECT")
                .projectId(projectId())
                .sessionId(sessionId())
                .importance("MEDIUM")
                .embeddingModel("text-embedding-v4")
                .metadata(metadataSupport.toJson(legacyMetadata))
                .accessCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private Map<String, Object> metadata(String name, String details, String authority, String updatePolicy) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", name);
        metadata.put("summary", details);
        metadata.put("facets", Map.of("name", name, "details", details));
        metadata.put("source", Map.of("source", "test", "authority", authority));
        metadata.put("updatePolicy", updatePolicy);
        return metadata;
    }

    private String content(String name, String details) {
        return "## Name\n" + name + "\n\n## Details\n" + details;
    }

    private String projectId() {
        return "22222222-2222-2222-2222-222222222222";
    }

    private String sessionId() {
        return "11111111-1111-1111-1111-111111111111";
    }
}
