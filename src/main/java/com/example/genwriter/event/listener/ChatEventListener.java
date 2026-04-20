package com.example.genwriter.event.listener;

import com.example.genwriter.agent.AgentEngine;
import com.example.genwriter.agent.AgentEngineFactory;
import com.example.genwriter.event.ChatEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 聊天事件监听器
 * 异步处理聊天事件，调用 Agent 引擎生成内容
 */
@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final AgentEngineFactory agentEngineFactory;

    /**
     * 异步处理聊天事件
     * @param event 聊天事件
     */
    @Async("taskExecutor")
    @EventListener
    public void handle(ChatEvent event) {
        log.info("接收到聊天事件：sessionId={}, type={}, documentId={}",
                event.getSessionId(), event.getType(), event.getDocumentId());

        try {
            // 创建 Agent 引擎实例
            AgentEngine agentEngine = agentEngineFactory.create(
                    event.getSessionId(),
                    event.getDocumentId()
            );

            // 运行 Agent 引擎
            agentEngine.run(event.getUserInput(), event.getType());

            log.info("写作事件处理完成：sessionId={}", event.getSessionId());
        } catch (Exception e) {
            log.error("写作事件处理失败：sessionId={}, error={}", event.getSessionId(), e.getMessage(), e);
            // TODO: 发送错误事件到前端
        }
    }
}
