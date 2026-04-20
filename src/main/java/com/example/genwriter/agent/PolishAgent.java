package com.example.genwriter.agent;

import com.example.genwriter.config.LLMConfig;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 文本润色 Agent
 * 负责对已有文本进行润色、优化表达
 */
public class PolishAgent extends BaseAgent {

    public PolishAgent(ChatClient chatClient, LLMConfig llmConfig) {
        super(AgentType.POLISH, chatClient, llmConfig);
    }

    @Override
    protected String think(AgentExecutionContext context) {
        return """
                请先识别文本中的表达、逻辑和风格问题，再输出润色后的完整文本。
                在不改变原意的前提下提升语言流畅度、准确性和专业性。

                待润色文本：
                %s
                """.formatted(context.getCurrentInput());
    }

    @Override
    protected String act(AgentExecutionContext context) {
        return callLLM(context.getThought());
    }
}
