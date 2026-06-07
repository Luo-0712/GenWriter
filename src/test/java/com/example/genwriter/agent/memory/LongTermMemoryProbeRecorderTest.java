package com.example.genwriter.agent.memory;

import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryProbeRecorderTest {

    @Test
    void recordWriteDecision_ShouldStaySilentWhenProbeDisabled() {
        LongTermMemoryProperties properties = new LongTermMemoryProperties();
        ThoughtChainPublisher publisher = mock(ThoughtChainPublisher.class);
        LongTermMemoryProbeRecorder recorder = new LongTermMemoryProbeRecorder(properties, publisher);

        recorder.recordWriteDecision("s1", "create", "m1", "WORLD_SETTING",
                "key", "MODEL_EXTRACTED", "MERGE", 1);

        assertTrue(recorder.recentEvents("s1").isEmpty());
        verify(publisher, never()).publishTraceStart(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordRetrieval_ShouldCacheAndPublishWhenProbeEnabled() {
        LongTermMemoryProperties properties = new LongTermMemoryProperties();
        properties.getProbe().setEnabled(true);
        ThoughtChainPublisher publisher = mock(ThoughtChainPublisher.class);
        when(publisher.publishTraceStart(eq("s1"), any(), any(), any(), any(), any()))
                .thenReturn("span-1");
        LongTermMemoryProbeRecorder recorder = new LongTermMemoryProbeRecorder(properties, publisher);

        recorder.recordRetrieval("s1", List.of("query"), List.of("WORLD_SETTING"),
                List.of(Map.of("resultCount", 1)), List.of(Map.of("id", "m1")), 1);

        List<Map<String, Object>> events = recorder.recentEvents("s1");
        assertEquals(1, events.size());
        assertEquals("retrieval", events.get(0).get("eventType"));
        verify(publisher).publishTraceComplete(eq("s1"), eq("span-1"), any(), any());
    }
}
