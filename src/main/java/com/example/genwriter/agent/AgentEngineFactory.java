package com.example.genwriter.agent;

import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 引擎工厂
 * 根据写作类型创建对应的 Agent 引擎实例
 */
@Slf4j
@Component
@AllArgsConstructor
public class AgentEngineFactory {

    private final WritingAgent writingAgent;
    private final OutlineAgent outlineAgent;
    private final PolishAgent polishAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final SseService sseService;

    /**
     * 创建 Agent 引擎实例
     * @param sessionId 会话 ID
     * @param documentId 文档 ID（可选）
     * @return AgentEngine 实例
     */
    public AgentEngine create(String sessionId, String documentId) {
        return new AgentEngine(sessionId, documentId, sseService,
                writingAgent, outlineAgent, polishAgent, knowledgeAgent);
    }
}
