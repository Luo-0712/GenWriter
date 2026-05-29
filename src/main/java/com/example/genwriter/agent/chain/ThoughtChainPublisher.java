package com.example.genwriter.agent.chain;

import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ThoughtChainPublisher {

    private final SseService sseService;

    private final Map<String, Long> startTimes = new ConcurrentHashMap<>();

    public String publishStart(String sessionId, String nodeName, ChainNode.Type nodeType,
                               String parentId, Object input) {
        String nodeId = generateNodeId(nodeName);
        long now = System.currentTimeMillis();
        startTimes.put(nodeId, now);

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .nodeName(nodeName)
                .nodeType(nodeType.name())
                .parentId(parentId)
                .status(ChainNode.Status.STARTED.name())
                .input(input)
                .timestamp(now)
                .build();

        publishChainNode(sessionId, chainNode);
        return nodeId;
    }

    public void publishRunning(String sessionId, String nodeId, String statusText) {
        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.RUNNING.name())
                .output(statusText)
                .timestamp(System.currentTimeMillis())
                .build();

        publishChainNode(sessionId, chainNode);
    }

    public void publishComplete(String sessionId, String nodeId, Object output) {
        publishComplete(sessionId, nodeId, output, null);
    }

    public void publishComplete(String sessionId, String nodeId, Object output, String reasoningContent) {
        Long startTime = startTimes.remove(nodeId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.COMPLETED.name())
                .output(output)
                .reasoningContent(reasoningContent)
                .duration(duration)
                .timestamp(System.currentTimeMillis())
                .build();

        publishChainNode(sessionId, chainNode);
    }

    /**
     * 推送模型推理内容 chunk（实时）
     */
    public void publishReasoningChunk(String sessionId, String nodeId, String chunk) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .data(Map.of("nodeId", nodeId, "reasoningChunk", chunk))
                            .statusText("模型思考中...")
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Reasoning chunk publish failed: {}", e.getMessage());
        }
    }

    public void publishError(String sessionId, String nodeId, String error) {
        Long startTime = startTimes.remove(nodeId);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .status(ChainNode.Status.ERROR.name())
                .error(error)
                .duration(duration)
                .timestamp(System.currentTimeMillis())
                .build();

        publishChainNode(sessionId, chainNode);
    }

    public void publishDirect(String sessionId, String nodeId, String nodeName,
                              ChainNode.Type nodeType, String parentId,
                              ChainNode.Status status, Object input, Object output,
                              Long duration) {
        ChainNode chainNode = ChainNode.builder()
                .nodeId(nodeId)
                .nodeName(nodeName)
                .nodeType(nodeType.name())
                .parentId(parentId)
                .status(status.name())
                .input(input)
                .output(output)
                .duration(duration)
                .timestamp(System.currentTimeMillis())
                .build();

        publishChainNode(sessionId, chainNode);
    }

    private void publishChainNode(String sessionId, ChainNode chainNode) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_CHAIN_EVENT)
                    .payload(SseMessage.Payload.builder()
                            .data(chainNode)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("Chain node publish failed: {}", e.getMessage());
        }
    }

    private String generateNodeId(String nodeName) {
        return nodeName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
