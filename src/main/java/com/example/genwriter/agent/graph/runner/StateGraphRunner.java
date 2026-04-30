package com.example.genwriter.agent.graph.runner;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.example.genwriter.agent.graph.checkpoint.GraphCheckpointProperties;
import com.example.genwriter.agent.graph.checkpoint.RedisCheckpointSaver;
import com.example.genwriter.agent.memory.MemoryProperties;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.MemoryExtractionService;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * StateGraph 执行器
 * 封装 Graph 的编译、调用和错误处理
 * 执行过程中通过 SSE 实时推送各阶段状态给前端
 */
@Slf4j
@Component
public class StateGraphRunner {

    private final StateGraph intentRouterGraph;
    private final StateGraph supervisorGraph;
    private final SupervisorModeProperties supervisorProperties;
    private final SseService sseService;
    private final MessageService messageService;
    private final RedisCheckpointSaver checkpointSaver;
    private final GraphCheckpointProperties checkpointProperties;
    private final RedisChatMemory chatMemory;
    private final MemoryProperties memoryProperties;
    private final MemoryExtractionService memoryExtractionService;
    private final LongTermMemoryProperties longTermMemoryProperties;

    private volatile CompiledGraph compiledGraph;
    private volatile CompiledGraph compiledSupervisorGraph;

    public StateGraphRunner(@Qualifier("intentRouterGraph") StateGraph intentRouterGraph,
                            @Qualifier("supervisorGraph") StateGraph supervisorGraph,
                            SupervisorModeProperties supervisorProperties,
                            SseService sseService,
                            MessageService messageService,
                            RedisCheckpointSaver checkpointSaver,
                            GraphCheckpointProperties checkpointProperties,
                            RedisChatMemory chatMemory,
                            MemoryProperties memoryProperties,
                            MemoryExtractionService memoryExtractionService,
                            LongTermMemoryProperties longTermMemoryProperties) {
        this.intentRouterGraph = intentRouterGraph;
        this.supervisorGraph = supervisorGraph;
        this.supervisorProperties = supervisorProperties;
        this.sseService = sseService;
        this.messageService = messageService;
        this.checkpointSaver = checkpointSaver;
        this.checkpointProperties = checkpointProperties;
        this.chatMemory = chatMemory;
        this.memoryProperties = memoryProperties;
        this.memoryExtractionService = memoryExtractionService;
        this.longTermMemoryProperties = longTermMemoryProperties;
    }

    private StateGraph activeGraph() {
        return supervisorProperties.isEnabled() ? supervisorGraph : intentRouterGraph;
    }

    private CompiledGraph getCompiledGraph() throws Exception {
        CompiledGraph target = activeGraph() == supervisorGraph ? compiledSupervisorGraph : compiledGraph;
        StateGraph source = activeGraph();

        if (target == null) {
            synchronized (this) {
                target = activeGraph() == supervisorGraph ? compiledSupervisorGraph : compiledGraph;
                if (target == null) {
                    if (checkpointProperties.isEnabled()) {
                        SaverConfig saverConfig = SaverConfig.builder()
                                .register("redis", checkpointSaver)
                                .build();
                        CompileConfig compileConfig = CompileConfig.builder()
                                .saverConfig(saverConfig)
                                .build();
                        target = source.compile(compileConfig);
                    } else {
                        target = source.compile();
                    }

                    if (activeGraph() == supervisorGraph) {
                        compiledSupervisorGraph = target;
                    } else {
                        compiledGraph = target;
                    }
                    log.info("CompiledGraph 已初始化: mode={}, checkpoint={}",
                            activeGraph() == supervisorGraph ? "supervisor" : "workflow",
                            checkpointProperties.isEnabled());
                }
            }
        }
        return target;
    }

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
        publishStatus(sessionId, "【任务启动】开始处理用户请求...");

        try {
            messageService.createMessage(sessionId, "user", userInput);

            String context = buildContextFromMemory(sessionId);

            Map<String, Object> inputs = Map.of(
                    "sessionId", sessionId,
                    "documentId", documentId != null ? documentId : "",
                    "userInput", userInput,
                    "kbId", kbId != null ? kbId : "",
                    "writingType", writingType != null ? writingType : "CREATE",
                    "context", context
            );

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .build();

            CompiledGraph graph = getCompiledGraph();
            Optional<OverAllState> result = graph.call(inputs, config);

            if (result.isPresent()) {
                OverAllState state = result.get();
                String finalOutput = state.value("finalOutput", String.class).orElse(null);

                publishStatus(sessionId, "【任务完成】");

                if (finalOutput != null && !finalOutput.isBlank()) {
                    messageService.createMessage(sessionId, "assistant", finalOutput);
                    saveToMemory(sessionId, userInput, finalOutput);

                    if (longTermMemoryProperties.isEnabled()) {
                        try {
                            memoryExtractionService.extractAsync(sessionId, documentId, userInput, finalOutput);
                        } catch (Exception e) {
                            log.warn("触发长期记忆提取失败: sessionId={}", sessionId, e);
                        }
                    }
                }
            } else {
                publishStatus(sessionId, "【任务完成】未产生输出");
            }

        } catch (Exception e) {
            log.error("StateGraph 执行失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            publishStatus(sessionId, "【任务失败】" + e.getMessage());
            sendErrorMessage(sessionId, "处理失败：" + e.getMessage());
        }
    }

    private String buildContextFromMemory(String sessionId) {
        try {
            List<Message> history = chatMemory.getAllMessages(sessionId);
            if (history.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("以下为本次会话的历史对话记录：\n");
            for (Message msg : history) {
                String role = msg.getMessageType().getValue();
                sb.append("[").append(role).append("]: ")
                        .append(msg.getText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("加载对话历史失败: sessionId={}", sessionId, e);
            return "";
        }
    }

    private void saveToMemory(String sessionId, String userInput, String assistantOutput) {
        try {
            List<Message> messages = List.of(
                    new UserMessage(userInput),
                    new AssistantMessage(assistantOutput)
            );
            chatMemory.add(sessionId, messages);
            log.debug("对话记忆已保存: sessionId={}", sessionId);
        } catch (Exception e) {
            log.warn("保存对话记忆失败: sessionId={}", sessionId, e);
        }
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
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
