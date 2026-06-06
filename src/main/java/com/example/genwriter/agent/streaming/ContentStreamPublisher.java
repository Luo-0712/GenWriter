package com.example.genwriter.agent.streaming;

import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentStreamPublisher {

    private static final long SNAPSHOT_INTERVAL_MS = 1000L;
    private static final int SNAPSHOT_CHAR_DELTA = 800;

    private final SseService sseService;
    private final ConcurrentMap<String, StreamState> states = new ConcurrentHashMap<>();
    private final AtomicLong revisionGenerator = new AtomicLong();

    public void startStage(String sessionId, String nodeId, Stage stage) {
        if (!isPublishable(sessionId, nodeId, stage)) {
            return;
        }
        long now = System.currentTimeMillis();
        states.put(key(sessionId, nodeId, stage), new StreamState(now, 0));
        publish(sessionId, payload(stage, "stage_start", nodeId, null, null,
                "RUNNING", true, false));
    }

    public void publishDelta(String sessionId, String nodeId, Stage stage, String delta, CharSequence currentContent) {
        if (!isPublishable(sessionId, nodeId, stage) || delta == null || delta.isEmpty()) {
            return;
        }
        publish(sessionId, payload(stage, "delta", nodeId, null, delta,
                "RUNNING", false, false));

        String stateKey = key(sessionId, nodeId, stage);
        StreamState state = states.computeIfAbsent(stateKey,
                ignored -> new StreamState(System.currentTimeMillis(), 0));
        String snapshot = currentContent != null ? currentContent.toString() : "";
        long now = System.currentTimeMillis();
        int snapshotLength = snapshot.length();
        if (now - state.lastSnapshotAt >= SNAPSHOT_INTERVAL_MS
                || snapshotLength - state.lastSnapshotLength >= SNAPSHOT_CHAR_DELTA) {
            state.lastSnapshotAt = now;
            state.lastSnapshotLength = snapshotLength;
            publish(sessionId, payload(stage, "snapshot", nodeId, snapshot, null,
                    "RUNNING", false, false));
        }
    }

    public void completeStage(String sessionId, String nodeId, Stage stage, String content) {
        if (!isPublishable(sessionId, nodeId, stage)) {
            return;
        }
        states.remove(key(sessionId, nodeId, stage));
        publish(sessionId, payload(stage, "stage_complete", nodeId, content != null ? content : "",
                null, "COMPLETED", false, false));
    }

    public void publishFinal(String sessionId, String content, Object document, Object sources, String resourceId) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }

        Map<String, Object> data = payload(Stage.FINAL, "final", null, content,
                null, "COMPLETED", false, true);
        if (document != null) {
            data.put("document", document);
        }
        if (sources != null) {
            data.put("sources", sources);
        }

        SseMessage.Metadata metadata = resourceId != null && !resourceId.isBlank()
                ? SseMessage.Metadata.builder().resourceId(resourceId).build()
                : null;

        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(data)
                            .build())
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.debug("Final content publish failed: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    private void publish(String sessionId, Map<String, Object> data) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(data)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Content stream publish failed: sessionId={}, mode={}, error={}",
                    sessionId, data.get("mode"), e.getMessage());
        }
    }

    private Map<String, Object> payload(Stage stage, String mode, String nodeId, String content,
                                        String delta, String status, boolean replaceOnStageStart,
                                        boolean finalContent) {
        Map<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        data.put("artifact", true);
        data.put("finalContent", finalContent);
        data.put("stage", stage.value);
        data.put("stageLabel", stage.label);
        data.put("mode", mode);
        data.put("status", status);
        data.put("revision", revisionGenerator.incrementAndGet());
        data.put("updatedAt", now);
        if (nodeId != null && !nodeId.isBlank()) {
            data.put("nodeId", nodeId);
        }
        if (content != null) {
            data.put("content", content);
        }
        if (delta != null) {
            data.put("delta", delta);
        }
        if (replaceOnStageStart) {
            data.put("replaceOnStageStart", true);
        }
        return data;
    }

    private boolean isPublishable(String sessionId, String nodeId, Stage stage) {
        return sessionId != null && !sessionId.isBlank()
                && nodeId != null && !nodeId.isBlank()
                && stage != null;
    }

    private String key(String sessionId, String nodeId, Stage stage) {
        return sessionId + "::" + nodeId + "::" + stage.value;
    }

    public enum Stage {
        OUTLINE("outline", "结构草稿"),
        DRAFT("draft", "初稿"),
        POLISH("polish", "润色稿"),
        DIRECT_ANSWER("direct_answer", "回答"),
        FINAL("final", "最终稿");

        private final String value;
        private final String label;

        Stage(String value, String label) {
            this.value = value;
            this.label = label;
        }
    }

    private static final class StreamState {
        private long lastSnapshotAt;
        private int lastSnapshotLength;

        private StreamState(long lastSnapshotAt, int lastSnapshotLength) {
            this.lastSnapshotAt = lastSnapshotAt;
            this.lastSnapshotLength = lastSnapshotLength;
        }
    }
}
