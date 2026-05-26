package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 发布节点
 * 将 Graph 执行结果发布到 SSE 频道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SsePublishNode implements NodeAction {

    private final SseService sseService;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String documentId = state.value("documentId", String.class).orElse(null);
        String finalOutput = state.value("finalOutput", String.class).orElse("");

        if (finalOutput != null && !finalOutput.isBlank()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", finalOutput);

            String researchSourcesJson = state.value("researchSources", String.class).orElse(null);
            if (researchSourcesJson != null && !researchSourcesJson.isBlank()) {
                try {
                    List<Map<String, String>> sources = objectMapper.readValue(
                            researchSourcesJson, new TypeReference<>() {});
                    if (!sources.isEmpty()) {
                        data.put("sources", sources);
                    }
                } catch (Exception e) {
                    log.warn("解析 researchSources 失败: {}", e.getMessage());
                }
            }

            SseMessage message = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .data(data)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .resourceId(documentId)
                            .build())
                    .build();

            sseService.publish(sessionId, message);
            log.debug("SSE 内容已发布: sessionId={}, length={}", sessionId, finalOutput.length());
        }

        // 发送完成信号
        sseService.complete(sessionId);
        log.debug("SSE 完成信号已发送: sessionId={}", sessionId);

        return Map.of("currentNode", "SsePublishNode");
    }
}
