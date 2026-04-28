package com.example.genwriter.agent.writerflow;

import com.example.genwriter.agent.core.AgentExecutionContext;
import com.example.genwriter.agent.AgentType;
import com.example.genwriter.agent.core.BaseAgent;
import com.example.genwriter.config.LLMConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 写作助手 Agent
 * 负责根据用户需求生成、续写文本内容
 */
public class WritingAgent extends BaseAgent {

    public WritingAgent(ChatClient chatClient, LLMConfig llmConfig, ChatMemory chatMemory) {
        super(AgentType.WRITING, chatClient, llmConfig, chatMemory);
    }

    @Override
    protected String think(AgentExecutionContext context) {
        return """
                请先分析用户的写作目标、场景、语气和结构要求，再直接输出最终成文内容。
                如果用户要求续写，请保持上下文风格一致并自然衔接。

                用户需求：
                %s
                """.formatted(context.getCurrentInput());
    }

    @Override
    protected String act(AgentExecutionContext context) {
        return callLLM(context.getThought(), context.getSessionId());
    }
}
