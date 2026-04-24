package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 错误处理节点
 * 统一处理 Graph 执行过程中的错误，发送 ERROR SSE 消息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorHandlerNode implements NodeAction {

    private final SseService sseService;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String errorMessage = state.value("errorMessage", String.class).orElse("处理失败，请稍后重试");

        log.error("错误处理节点执行: sessionId={}, error={}", sessionId, errorMessage);

        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.ERROR)
                .payload(SseMessage.Payload.builder()
                        .data(errorMessage)
                        .build())
                .build();

        sseService.publish(sessionId, message);
        sseService.complete(sessionId);

        return Map.of("currentNode", "ErrorHandlerNode");
    }
}
