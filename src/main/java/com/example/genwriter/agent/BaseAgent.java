package com.example.genwriter.agent;

import com.example.genwriter.config.LLMConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Agent 基类
 * 通过模板方法统一控制 ReAct 的执行骨架，子类只负责实现思考和动作。
 */
@Slf4j
@Getter
public abstract class BaseAgent {

    private final AgentType agentType;
    private final ChatClient chatClient;
    protected final LLMConfig llmConfig;

    protected BaseAgent(AgentType agentType, ChatClient chatClient, LLMConfig llmConfig) {
        this.agentType = agentType;
        this.chatClient = chatClient;
        this.llmConfig = llmConfig;
    }

    /**
     * 统一执行入口，按 ReAct 的思考 -> 执行 -> 观察节奏推进。
     */
    public final String execute(String input) {
        AgentExecutionContext context = new AgentExecutionContext(agentType, input);

        try {
            context.transitionTo(AgentState.THINKING);

            for (int round = 1; round <= maxRounds(); round++) {
                context.nextRound();
                context.transitionTo(AgentState.PLANNING);

                String thought = think(context);
                context.recordThought(thought);
                log.debug("Agent {} 第 {} 轮 thought: {}", agentType, round, thought);

                context.transitionTo(AgentState.EXECUTING);
                String observation = act(context);
                context.recordObservation(observation);
                log.debug("Agent {} 第 {} 轮 observation: {}", agentType, round, observation);

                if (!shouldContinue(context)) {
                    context.transitionTo(AgentState.FINISHED);
                    return observation;
                }

                context.updateCurrentInput(buildNextInput(context));
                context.transitionTo(AgentState.THINKING);
            }

            context.transitionTo(AgentState.FINISHED);
            return context.getObservation();
        } catch (Exception e) {
            context.transitionTo(AgentState.ERROR);
            log.error("Agent {} 执行失败，state={}", agentType, context.getState(), e);
            throw e;
        }
    }

    /**
     * 子类负责根据输入生成本轮思考结果或执行计划。
     */
    protected abstract String think(AgentExecutionContext context);

    /**
     * 子类负责根据思考结果执行动作，并返回观察结果。
     */
    protected abstract String act(AgentExecutionContext context);

    /**
     * 默认单轮执行，子类可按需扩展为多轮 ReAct。
     */
    protected boolean shouldContinue(AgentExecutionContext context) {
        return false;
    }

    /**
     * 多轮场景下用于构造下一轮输入。
     */
    protected String buildNextInput(AgentExecutionContext context) {
        return context.getObservation();
    }

    /**
     * 默认只跑一轮，复杂 Agent 可覆盖。
     */
    protected int maxRounds() {
        return 1;
    }

    /**
     * 统一的 LLM 调用封装。
     */
    protected String callLLM(String input) {
        String prompt = input == null ? "" : input.trim();
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
