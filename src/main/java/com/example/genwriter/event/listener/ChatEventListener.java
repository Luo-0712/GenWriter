package com.example.genwriter.event.listener;

import com.example.genwriter.agent.AgentEngine;
import com.example.genwriter.agent.AgentEngineFactory;
import com.example.genwriter.agent.graph.runner.StateGraphRunner;
import com.example.genwriter.event.ChatEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 聊天事件监听器
 * 异步处理聊天事件，支持 StateGraph 新流程和旧 AgentEngine 兼容流程
 */
@Slf4j
@Component
public class ChatEventListener {

    private final AgentEngineFactory agentEngineFactory;
    private final StateGraphRunner stateGraphRunner;

    @Value("${genwriter.graph.enabled:false}")
    private boolean graphEnabled;

    public ChatEventListener(AgentEngineFactory agentEngineFactory, StateGraphRunner stateGraphRunner) {
        this.agentEngineFactory = agentEngineFactory;
        this.stateGraphRunner = stateGraphRunner;
    }

    /**
     * 异步处理聊天事件
     * @param event 聊天事件
     */
    @Async("taskExecutor")
    @EventListener
    public void handle(ChatEvent event) {
        log.info("接收到聊天事件：sessionId={}, type={}, documentId={}, graphEnabled={}",
                event.getSessionId(), event.getType(), event.getDocumentId(), graphEnabled);

        if (graphEnabled) {
            handleWithStateGraph(event);
        } else {
            handleWithAgentEngine(event);
        }
    }

    private void handleWithStateGraph(ChatEvent event) {
        try {
            stateGraphRunner.run(
                    event.getSessionId(),
                    event.getDocumentId(),
                    event.getUserInput(),
                    event.getKbId(),
                    event.getType() != null ? event.getType().name() : "CREATE"
            );
            log.info("StateGraph 处理完成：sessionId={}", event.getSessionId());
        } catch (Exception e) {
            log.error("StateGraph 处理失败，降级到 AgentEngine：sessionId={}, error={}",
                    event.getSessionId(), e.getMessage(), e);
            handleWithAgentEngine(event);
        }
    }

    private void handleWithAgentEngine(ChatEvent event) {
        try {
            AgentEngine agentEngine = agentEngineFactory.create(
                    event.getSessionId(),
                    event.getDocumentId()
            );
            agentEngine.run(event.getUserInput(), event.getType(), event.getKbId());
            log.info("AgentEngine 处理完成：sessionId={}", event.getSessionId());
        } catch (Exception e) {
            log.error("AgentEngine 处理失败：sessionId={}, error={}", event.getSessionId(), e.getMessage(), e);
        }
    }
}
