package com.example.genwriter.service;

import com.example.genwriter.event.ChatEvent;
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
        publisher.publishEvent(new ChatEvent(sessionId, null, userInput, null, type));
    }

    /**
     * 发布聊天事件（带文档 ID）
     * @param sessionId 会话 ID
     * @param documentId 文档 ID
     * @param userInput 用户输入
     * @param type 聊天类型
     */
    public void submitChatTask(String sessionId, String documentId, String userInput, ChatEvent.WritingType type) {
        publisher.publishEvent(new ChatEvent(sessionId, documentId, userInput, null, type));
    }

    /**
     * 发布知识库问答事件
     * @param sessionId 会话 ID
     * @param kbId 知识库 ID
     * @param userInput 用户输入
     */
    public void submitKnowledgeQaTask(String sessionId, String kbId, String userInput) {
        publisher.publishEvent(new ChatEvent(sessionId, null, userInput, kbId, ChatEvent.WritingType.KNOWLEDGE_QA));
    }
}
