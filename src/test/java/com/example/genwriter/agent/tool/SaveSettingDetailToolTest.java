package com.example.genwriter.agent.tool;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SaveSettingDetailToolTest {

    @Mock
    private LongTermMemoryService memoryService;

    @Mock
    private TaskSessionMapper taskSessionMapper;

    @Mock
    private ThoughtChainPublisher chainPublisher;

    @AfterEach
    void tearDown() {
        SessionContextHolder.clear();
    }

    @Test
    void applyWithToolContext_ShouldStoreMemoryForContextSession() {
        SaveSettingDetailTool tool = new SaveSettingDetailTool(memoryService, taskSessionMapper, chainPublisher);
        TaskSession session = TaskSession.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .projectId("22222222-2222-2222-2222-222222222222")
                .build();
        when(taskSessionMapper.selectById(session.getId())).thenReturn(session);

        SaveSettingDetailTool.SaveSettingDetailInput input = new SaveSettingDetailTool.SaveSettingDetailInput(
                "WORLD_SETTING",
                "Lunar relay",
                "A quantum relay near the moon controls all interplanetary latency.",
                "HIGH"
        );
        ToolContext toolContext = new ToolContext(AgentToolSupport.sessionContext(session.getId(), "span-1", "draft"));

        String result = tool.applyWithContext(input, toolContext);

        assertTrue(result.contains("\"success\":true"));
        var metadataCaptor = forClass(Map.class);
        verify(memoryService).storeMemory(
                any(String.class),
                eq(MemoryType.WORLD_SETTING),
                eq("PROJECT"),
                eq(session.getProjectId()),
                eq(session.getId()),
                eq("HIGH"),
                metadataCaptor.capture()
        );
        Map<?, ?> metadata = metadataCaptor.getValue();
        assertEquals("REPLACE", metadata.get("updatePolicy"));
        assertEquals("USER_EXPLICIT", ((Map<?, ?>) metadata.get("source")).get("authority"));
    }

    @Test
    void applyWithThreadLocalContext_ShouldKeepLegacyPath() {
        SaveSettingDetailTool tool = new SaveSettingDetailTool(memoryService, taskSessionMapper, chainPublisher);
        SessionContextHolder.set("33333333-3333-3333-3333-333333333333", "span-2", "outline");

        SaveSettingDetailTool.SaveSettingDetailInput input = new SaveSettingDetailTool.SaveSettingDetailInput(
                "CHARACTER_PROFILE",
                "林澈",
                "A propulsion engineer who distrusts autonomous navigation.",
                "MEDIUM"
        );

        String result = tool.apply(input);

        assertTrue(result.contains("\"success\":true"));
        verify(memoryService).storeMemory(
                any(String.class),
                eq(MemoryType.CHARACTER_PROFILE),
                eq("GLOBAL"),
                eq(null),
                eq("33333333-3333-3333-3333-333333333333"),
                eq("MEDIUM"),
                anyMetadata()
        );
    }

    @Test
    void applyWithoutSession_ShouldFailAndNotStore() {
        SaveSettingDetailTool tool = new SaveSettingDetailTool(memoryService, taskSessionMapper, chainPublisher);
        SaveSettingDetailTool.SaveSettingDetailInput input = new SaveSettingDetailTool.SaveSettingDetailInput(
                "FORESHADOWING",
                "silent beacon",
                "The beacon pulses only when no observer is present.",
                "LOW"
        );

        String result = tool.apply(input);

        assertTrue(result.contains("\"success\":false"));
        verify(memoryService, never()).storeMemory(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void applyWithToolContext_ShouldRestorePreviousThreadLocalContext() {
        SaveSettingDetailTool tool = new SaveSettingDetailTool(memoryService, taskSessionMapper, chainPublisher);
        SessionContextHolder.set("previous-session", "previous-span", "previous-agent");
        ToolContext toolContext = new ToolContext(AgentToolSupport.sessionContext(
                "44444444-4444-4444-4444-444444444444", "span-3", "draft"));

        SaveSettingDetailTool.SaveSettingDetailInput input = new SaveSettingDetailTool.SaveSettingDetailInput(
                "WORLD_SETTING",
                "orbital court",
                "The orbital court licenses every AI habitat.",
                "HIGH"
        );

        tool.applyWithContext(input, toolContext);

        assertEquals("previous-session", SessionContextHolder.get());
        assertEquals("previous-span", SessionContextHolder.getCurrentSpanId());
        assertEquals("previous-agent", SessionContextHolder.getCurrentAgentName());
    }

    private Map<String, Object> anyMetadata() {
        return any();
    }
}
