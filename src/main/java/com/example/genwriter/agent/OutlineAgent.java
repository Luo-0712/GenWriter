package com.example.genwriter.agent;

import com.example.genwriter.config.LLMConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

/**
 * 大纲生成 Agent
 * 负责根据主题或内容生成文档大纲结构
 */
public class OutlineAgent extends BaseAgent {

    public OutlineAgent(ChatClient chatClient, LLMConfig llmConfig, ChatMemory chatMemory) {
        super(AgentType.OUTLINE, chatClient, llmConfig, chatMemory);
    }

    @Override
    protected String think(AgentExecutionContext context) {
        return """
                请先分析主题、目标读者和内容层次，再输出清晰的多级大纲。
                大纲应突出章节关系，并保证结构完整、层次分明。

                主题或需求：
                %s
                """.formatted(context.getCurrentInput());
    }

    @Override
    protected String act(AgentExecutionContext context) {
        return callLLM(context.getThought(), context.getSessionId());
    }
}
