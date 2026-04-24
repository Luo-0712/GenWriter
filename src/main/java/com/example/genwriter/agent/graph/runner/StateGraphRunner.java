package com.example.genwriter.agent.graph.runner;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * StateGraph 执行器
 * 封装 Graph 的编译、调用和错误处理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateGraphRunner {

    private final StateGraph intentRouterGraph;
    private final SseService sseService;
    private final MessageService messageService;

    /**
     * 执行 StateGraph
     *
     * @param sessionId  会话 ID
     * @param documentId 文档 ID
     * @param userInput  用户输入
     * @param kbId       知识库 ID
     * @param writingType 写作类型（可选，用于兼容旧流程）
     */
    public void run(String sessionId, String documentId, String userInput, String kbId, String writingType) {
        log.info("StateGraph 执行开始: sessionId={}, type={}", sessionId, writingType);

        try {
            messageService.createMessage(sessionId, "user", userInput);

            CompiledGraph compiledGraph = intentRouterGraph.compile();

            Map<String, Object> inputs = Map.of(
                    "sessionId", sessionId,
                    "documentId", documentId != null ? documentId : "",
                    "userInput", userInput,
                    "kbId", kbId != null ? kbId : "",
                    "writingType", writingType != null ? writingType : "CREATE"
            );

            Optional<OverAllState> result = compiledGraph.call(inputs);

            if (result.isPresent()) {
                OverAllState state = result.get();
                String finalOutput = state.value("finalOutput", String.class).orElse(null);
                
                log.info("StateGraph 执行完成: sessionId={}, finalNode={}",
                        sessionId, state.value("currentNode").orElse("UNKNOWN"));
                
                if (finalOutput != null && !finalOutput.isBlank()) {
                    messageService.createMessage(sessionId, "assistant", finalOutput);
                    log.debug("AI 响应消息已持久化: sessionId={}", sessionId);
                }
            } else {
                log.warn("StateGraph 执行返回空结果: sessionId={}", sessionId);
            }

        } catch (Exception e) {
            log.error("StateGraph 执行失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            sendErrorMessage(sessionId, "处理失败：" + e.getMessage());
        }
    }

    private void sendErrorMessage(String sessionId, String errorMessage) {
        try {
            SseMessage message = SseMessage.builder()
                    .type(SseMessage.Type.ERROR)
                    .payload(SseMessage.Payload.builder()
                            .data(errorMessage)
                            .build())
                    .build();
            sseService.publish(sessionId, message);
            sseService.complete(sessionId);
        } catch (Exception ex) {
            log.error("发送错误消息失败: sessionId={}", sessionId, ex);
        }
    }
}
