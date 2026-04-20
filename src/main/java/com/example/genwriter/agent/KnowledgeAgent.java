package com.example.genwriter.agent;

import com.example.genwriter.config.LLMConfig;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 知识库问答 Agent
 * 负责基于知识库内容进行问答
 */
public class KnowledgeAgent extends BaseAgent {

    public KnowledgeAgent(ChatClient chatClient, LLMConfig llmConfig) {
        super(AgentType.KNOWLEDGE, chatClient, llmConfig);
    }

    @Override
    protected String think(AgentExecutionContext context) {
        return """
                请先理解用户问题，梳理回答所需的关键信息，再基于知识库检索结果给出准确回答。
                如果当前上下文无法支持结论，请明确说明信息不足，不要臆造事实。

                用户问题：
                %s
                """.formatted(context.getCurrentInput());
    }

    @Override
    protected String act(AgentExecutionContext context) {
        return callLLM(context.getThought());
    }
}
