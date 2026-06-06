package com.example.genwriter.service;

import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.model.dto.MultimodalContent;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 聊天服务
 * 发布聊天事件，触发异步处理
 */
@Service
@AllArgsConstructor
public class ChatService {

    private final ApplicationEventPublisher publisher;

    /**
     * 发布聊天事件
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param type 聊天类型
     */
    public void submitChatTask(String sessionId, String userInput, ChatEvent.WritingType type) {
        submitChatTask(sessionId, MultimodalContent.ofText(userInput), type);
    }

    /**
     * 发布聊天事件（带文档 ID）
     * @param sessionId 会话 ID
     * @param documentId 文档 ID
     * @param userInput 用户输入
     * @param type 聊天类型
     */
    public void submitChatTask(String sessionId, String documentId, String userInput, ChatEvent.WritingType type) {
        publisher.publishEvent(new ChatEvent(sessionId, documentId, MultimodalContent.ofText(userInput), null, type, true));
    }

    /**
     * 发布聊天事件（带 webSearch 控制）
     */
    public void submitChatTask(String sessionId, String userInput, ChatEvent.WritingType type, boolean webSearch) {
        submitChatTask(sessionId, MultimodalContent.ofText(userInput), type, webSearch, null);
    }

    /**
     * 发布聊天事件（带 webSearch 控制和 kbId）
     */
    public void submitChatTask(String sessionId, String userInput, ChatEvent.WritingType type, boolean webSearch, String kbId) {
        submitChatTask(sessionId, MultimodalContent.ofText(userInput), type, webSearch, kbId);
    }

    /**
     * 发布聊天事件（MultimodalContent 版本）
     */
    public void submitChatTask(String sessionId, MultimodalContent userInput, ChatEvent.WritingType type) {
        publisher.publishEvent(new ChatEvent(sessionId, null, userInput, null, type, true));
    }

    /**
     * 发布聊天事件（MultimodalContent 版本，带 webSearch 控制和 kbId）
     */
    public void submitChatTask(String sessionId, MultimodalContent userInput, ChatEvent.WritingType type, boolean webSearch, String kbId) {
        publisher.publishEvent(new ChatEvent(sessionId, null, userInput, kbId, type, webSearch));
    }

    /**
     * 发布聊天事件（MultimodalContent 版本，带文档上下文、webSearch 控制和 kbId）
     */
    public void submitChatTask(String sessionId, String documentId, MultimodalContent userInput,
                               ChatEvent.WritingType type, boolean webSearch, String kbId) {
        publisher.publishEvent(new ChatEvent(sessionId, documentId, userInput, kbId, type, webSearch));
    }

    /**
     * 发布知识库问答事件
     * @param sessionId 会话 ID
     * @param kbId 知识库 ID
     * @param userInput 用户输入
     */
    public void submitKnowledgeQaTask(String sessionId, String kbId, String userInput) {
        publisher.publishEvent(new ChatEvent(sessionId, null, MultimodalContent.ofText(userInput), kbId, ChatEvent.WritingType.KNOWLEDGE_QA, true));
    }
}
