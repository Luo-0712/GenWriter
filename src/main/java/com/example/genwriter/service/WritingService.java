package com.example.genwriter.service;

import com.example.genwriter.event.WritingEvent;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 写作服务
 * 发布写作事件，触发异步处理
 */
@Service
@AllArgsConstructor
public class WritingService {

    private final ApplicationEventPublisher publisher;

    /**
     * 发布写作事件
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param type 写作类型
     */
    public void submitWritingTask(String sessionId, String userInput, WritingEvent.WritingType type) {
        publisher.publishEvent(new WritingEvent(sessionId, null, userInput, type));
    }

    /**
     * 发布写作事件（带文档 ID）
     * @param sessionId 会话 ID
     * @param documentId 文档 ID
     * @param userInput 用户输入
     * @param type 写作类型
     */
    public void submitWritingTask(String sessionId, String documentId, String userInput, WritingEvent.WritingType type) {
        publisher.publishEvent(new WritingEvent(sessionId, documentId, userInput, type));
    }
}
