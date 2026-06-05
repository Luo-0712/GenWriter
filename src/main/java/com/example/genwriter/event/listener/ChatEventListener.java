package com.example.genwriter.event.listener;

import com.example.genwriter.agent.graph.runner.StateGraphRunner;
import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.service.TitleGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 聊天事件监听器
 * 使用 Supervisor 模式处理所有聊天事件
 */
@Slf4j
@Component
public class ChatEventListener {

    private final StateGraphRunner stateGraphRunner;
    private final TitleGenerationService titleGenerationService;

    public ChatEventListener(StateGraphRunner stateGraphRunner,
                             TitleGenerationService titleGenerationService) {
        this.stateGraphRunner = stateGraphRunner;
        this.titleGenerationService = titleGenerationService;
    }

    @Async("taskExecutor")
    @EventListener
    public void handle(ChatEvent event) {
        log.info("接收到聊天事件：sessionId={}, type={}, documentId={}",
                event.getSessionId(), event.getType(), event.getDocumentId());

        titleGenerationService.generateAndSetTitle(event.getSessionId(), event.getUserInputText());

        try {
            stateGraphRunner.run(
                    event.getSessionId(),
                    event.getDocumentId(),
                    event.getUserInput(),
                    event.getKbId(),
                    event.getType() != null ? event.getType().name() : "AUTO",
                    event.isWebSearch()
            );
            log.info("Supervisor 处理完成：sessionId={}", event.getSessionId());
        } catch (Exception e) {
            log.error("Supervisor 处理失败：sessionId={}, error={}", event.getSessionId(), e.getMessage(), e);
        }
    }
}
