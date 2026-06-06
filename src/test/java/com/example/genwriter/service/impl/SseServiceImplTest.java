package com.example.genwriter.service.impl;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.sse.ChannelMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SseServiceImplTest {

    @Test
    void startRun_ShouldResetCompletedHistoryForSameSession() {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());
        String sessionId = "session-1";

        service.publish(sessionId, status("first"));
        service.complete(sessionId);
        long lastSequenceFromFirstRun = service.getLastSequenceId(sessionId);
        assertFalse(service.getHistory(sessionId, 0).isEmpty());

        service.startRun(sessionId);
        List<ChannelMessage> historyAfterReset = service.getHistory(sessionId, 0);

        assertEquals(0, historyAfterReset.size());
        assertEquals(lastSequenceFromFirstRun, service.getLastSequenceId(sessionId));

        service.publish(sessionId, status("second"));
        List<ChannelMessage> newHistory = service.getHistory(sessionId, lastSequenceFromFirstRun);
        assertEquals(1, newHistory.size());
        assertEquals(SseMessage.Type.AI_CHAIN_EVENT, newHistory.get(0).getPayload().getType());
    }

    private SseMessage status(String text) {
        return SseMessage.builder()
                .type(SseMessage.Type.AI_CHAIN_EVENT)
                .payload(SseMessage.Payload.builder()
                        .statusText(text)
                        .data(Map.of("status", text))
                        .build())
                .build();
    }
}
