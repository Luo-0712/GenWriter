package com.example.genwriter.agent.streaming;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ContentStreamPublisherTest {

    @Mock
    private SseService sseService;

    @Test
    void startStage_ShouldPublishStageStartPayload() {
        ContentStreamPublisher publisher = new ContentStreamPublisher(sseService);

        publisher.startStage("session-1", "node-1", ContentStreamPublisher.Stage.DRAFT);

        Map<String, Object> data = captureSinglePayload();
        assertEquals(true, data.get("artifact"));
        assertEquals(false, data.get("finalContent"));
        assertEquals("draft", data.get("stage"));
        assertEquals("初稿", data.get("stageLabel"));
        assertEquals("stage_start", data.get("mode"));
        assertEquals("RUNNING", data.get("status"));
        assertEquals(true, data.get("replaceOnStageStart"));
    }

    @Test
    void publishDelta_ShouldPublishDeltaAndSnapshotWhenThresholdReached() {
        ContentStreamPublisher publisher = new ContentStreamPublisher(sseService);
        StringBuilder content = new StringBuilder("x".repeat(801));

        publisher.publishDelta("session-1", "node-1",
                ContentStreamPublisher.Stage.POLISH, "chunk", content);

        List<Map<String, Object>> payloads = capturePayloads();
        assertEquals(2, payloads.size());
        assertEquals("delta", payloads.get(0).get("mode"));
        assertEquals("chunk", payloads.get(0).get("delta"));
        assertEquals("snapshot", payloads.get(1).get("mode"));
        assertEquals(content.toString(), payloads.get(1).get("content"));
    }

    @Test
    void completeStage_ShouldPublishCompleteSnapshot() {
        ContentStreamPublisher publisher = new ContentStreamPublisher(sseService);

        publisher.completeStage("session-1", "node-1",
                ContentStreamPublisher.Stage.DIRECT_ANSWER, "final answer");

        Map<String, Object> data = captureSinglePayload();
        assertEquals("direct_answer", data.get("stage"));
        assertEquals("stage_complete", data.get("mode"));
        assertEquals("COMPLETED", data.get("status"));
        assertEquals("final answer", data.get("content"));
    }

    @Test
    void publishDelta_ShouldIgnoreEmptyDelta() {
        ContentStreamPublisher publisher = new ContentStreamPublisher(sseService);

        publisher.publishDelta("session-1", "node-1",
                ContentStreamPublisher.Stage.DRAFT, "", new StringBuilder());

        verifyNoInteractions(sseService);
    }

    @Test
    void publishFinal_ShouldMarkFinalContentAndAttachSources() {
        ContentStreamPublisher publisher = new ContentStreamPublisher(sseService);
        List<Map<String, String>> sources = List.of(Map.of("title", "Source", "url", "https://example.test"));

        publisher.publishFinal("session-1", "done", Map.of("id", "doc-1"), sources, "doc-1");

        Map<String, Object> data = captureSinglePayload();
        assertEquals(true, data.get("artifact"));
        assertEquals(true, data.get("finalContent"));
        assertEquals("final", data.get("stage"));
        assertEquals("final", data.get("mode"));
        assertEquals("done", data.get("content"));
        assertTrue(data.containsKey("document"));
        assertFalse(((List<?>) data.get("sources")).isEmpty());
    }

    private Map<String, Object> captureSinglePayload() {
        List<Map<String, Object>> payloads = capturePayloads();
        assertEquals(1, payloads.size());
        return payloads.get(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturePayloads() {
        ArgumentCaptor<SseMessage> captor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, org.mockito.Mockito.atLeastOnce()).publish(eq("session-1"), captor.capture());
        return captor.getAllValues().stream()
                .map(message -> (Map<String, Object>) message.getPayload().getData())
                .toList();
    }
}
