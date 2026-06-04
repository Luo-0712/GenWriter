package com.example.genwriter.agent;

import com.example.genwriter.agent.core.BaseAgent;
import com.example.genwriter.agent.writerflow.KnowledgeAgent;
import com.example.genwriter.agent.writerflow.OutlineAgent;
import com.example.genwriter.agent.writerflow.PolishAgent;
import com.example.genwriter.agent.writerflow.WritingAgent;
import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 引擎
 * 统一调度各类 Agent，支持 SSE 实时推送（发布-订阅模式）
 */
@Slf4j
@AllArgsConstructor
public class AgentEngine {

    private final String sessionId;
    private final String documentId;
    private final SseService sseService;
    private final MessageService messageService;
    private final WritingAgent writingAgent;
    private final OutlineAgent outlineAgent;
    private final PolishAgent polishAgent;
    private final KnowledgeAgent knowledgeAgent;

    /**
     * 运行 Agent 引擎
     * @param userInput 用户输入
     * @param type 聊天类型
     */
    public void run(String userInput, ChatEvent.WritingType type) {
        run(MultimodalContent.ofText(userInput), type, null);
    }

    public void run(String userInput, ChatEvent.WritingType type, String kbId) {
        run(MultimodalContent.ofText(userInput), type, kbId);
    }

    public void run(MultimodalContent userInput, ChatEvent.WritingType type, String kbId) {
        log.info("Agent 引擎启动：sessionId={}, type={}, kbId={}", sessionId, type, kbId);

        try {
            messageService.createMessage(sessionId, "user", userInput.getTextOnly());

            publishStatusMessage(SseMessage.Type.AI_THINKING, "正在处理您的请求...", false);

            BaseAgent agent = getAgentByType(type);
            if (agent == null) {
                publishErrorMessage("不支持的写作类型：" + type);
                sseService.complete(sessionId);
                return;
            }

            publishStatusMessage(resolveStatusType(type), resolveStatusText(type), false);
            String result = agent.execute(userInput, kbId, sessionId);

            if (result != null) {
                publishContentMessage(result);
                messageService.createMessage(sessionId, "assistant", result);
                sseService.complete(sessionId);
            }

            log.info("Agent 引擎执行完成：sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("Agent 引擎执行失败：sessionId={}, error={}", sessionId, e.getMessage(), e);
            publishErrorMessage("处理失败：" + e.getMessage());
            sseService.complete(sessionId);
        }
    }

    /**
     * 根据类型获取对应的 Agent
     */
    private BaseAgent getAgentByType(ChatEvent.WritingType type) {
        return switch (type) {
            case AUTO, CREATE, CONTINUE -> writingAgent;
            case POLISH -> polishAgent;
            case KNOWLEDGE_QA -> knowledgeAgent;
            default -> null;
        };
    }

    private SseMessage.Type resolveStatusType(ChatEvent.WritingType type) {
        return switch (type) {
            case AUTO, CREATE, CONTINUE -> SseMessage.Type.AI_PLANNING;
            case POLISH, KNOWLEDGE_QA -> SseMessage.Type.AI_EXECUTING;
        };
    }

    private String resolveStatusText(ChatEvent.WritingType type) {
        return switch (type) {
            case AUTO, CREATE, CONTINUE -> "正在生成内容...";
            case POLISH -> "正在润色文本...";
            case KNOWLEDGE_QA -> "正在检索知识库...";
        };
    }

    /**
     * 发布状态消息到频道
     */
    private void publishStatusMessage(SseMessage.Type type, String statusText, Boolean done) {
        SseMessage message = SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .done(done)
                        .build())
                .build();
        sseService.publish(sessionId, message);
    }

    /**
     * 发布内容消息到频道
     */
    private void publishContentMessage(String content) {
        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .data(content)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .resourceId(documentId)
                        .build())
                .build();
        sseService.publish(sessionId, message);
    }

    /**
     * 发布错误消息到频道
     */
    private void publishErrorMessage(String error) {
        SseMessage message = SseMessage.builder()
                .type(SseMessage.Type.ERROR)
                .payload(SseMessage.Payload.builder()
                        .data(error)
                        .build())
                .build();
        sseService.publish(sessionId, message);
    }
}