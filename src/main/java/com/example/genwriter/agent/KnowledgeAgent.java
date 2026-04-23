package com.example.genwriter.agent;

import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.KnowledgeSearchInput;
import com.example.genwriter.config.LLMConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 知识库问答 Agent
 * 负责基于知识库内容进行问答，支持 function calling 检索知识库
 */
public class KnowledgeAgent extends BaseAgent {

    private final KnowledgeBaseTool knowledgeBaseTool;

    public KnowledgeAgent(ChatClient chatClient, LLMConfig llmConfig, ChatMemory chatMemory, KnowledgeBaseTool knowledgeBaseTool) {
        super(AgentType.KNOWLEDGE, chatClient, llmConfig, chatMemory);
        this.knowledgeBaseTool = knowledgeBaseTool;
    }

    @Override
    protected String think(AgentExecutionContext context) {
        String kbId = context.getKbId();
        String kbHint = (kbId != null && !kbId.isBlank())
                ? "（将使用知识库ID: " + kbId + " 进行检索）"
                : "（未指定知识库，将直接回答）";

        return """
                请先理解用户问题，梳理回答所需的关键信息，再基于知识库检索结果给出准确回答。
                如果当前上下文无法支持结论，请明确说明信息不足，不要臆造事实。
                %s

                用户问题：
                %s
                """.formatted(kbHint, context.getCurrentInput());
    }

    @Override
    protected String act(AgentExecutionContext context) {
        String kbId = context.getKbId();
        String sessionId = context.getSessionId();

        if (kbId == null || kbId.isBlank()) {
            return callLLM(context.getThought(), sessionId);
        }

        var spec = getChatClient().prompt()
                .user(context.getThought())
                .function("searchKnowledgeBase",
                        "根据查询语句从知识库中检索最相关的文本片段，用于回答用户问题。",
                        KnowledgeSearchInput.class,
                        input -> knowledgeBaseTool.search(input));

        if (memoryAdvisor != null && sessionId != null) {
            spec.advisors(memoryAdvisor)
                    .advisors(a -> a.param(
                            org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId));
        }

        return spec.call().content();
    }
}
