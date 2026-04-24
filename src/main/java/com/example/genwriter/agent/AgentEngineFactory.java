package com.example.genwriter.agent;

import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class AgentEngineFactory {

    private final WritingAgent writingAgent;
    private final OutlineAgent outlineAgent;
    private final PolishAgent polishAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final SseService sseService;
    private final MessageService messageService;

    public AgentEngine create(String sessionId, String documentId) {
        return new AgentEngine(sessionId, documentId, sseService, messageService,
                writingAgent, outlineAgent, polishAgent, knowledgeAgent);
    }
}
