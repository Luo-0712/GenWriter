package com.example.genwriter.agent.core;

import com.example.genwriter.agent.AgentState;
import com.example.genwriter.agent.AgentType;
import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.config.ModelCapabilityConfig;
import com.example.genwriter.model.dto.MultimodalContent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.Media;
import org.springframework.util.MimeType;

import java.util.List;

/**
 * Agent 基类
 * 通过模板方法统一控制 ReAct 的执行骨架，子类只负责实现思考和动作。
 * 集成 Spring AI ChatMemory 实现短期记忆。
 */
@Slf4j
@Getter
public abstract class BaseAgent {

    private final AgentType agentType;
    private final ChatClient chatClient;
    protected final LLMConfig llmConfig;
    private final ChatMemory chatMemory;
    protected final MessageChatMemoryAdvisor memoryAdvisor;
    protected final ModelCapabilityConfig modelCapabilityConfig;

    protected BaseAgent(AgentType agentType, ChatClient chatClient, LLMConfig llmConfig, ChatMemory chatMemory) {
        this.agentType = agentType;
        this.chatClient = chatClient;
        this.llmConfig = llmConfig;
        this.chatMemory = chatMemory;
        this.memoryAdvisor = chatMemory != null ? new MessageChatMemoryAdvisor(chatMemory) : null;
        this.modelCapabilityConfig = null;
    }

    protected BaseAgent(AgentType agentType, ChatClient chatClient, LLMConfig llmConfig, ChatMemory chatMemory, ModelCapabilityConfig modelCapabilityConfig) {
        this.agentType = agentType;
        this.chatClient = chatClient;
        this.llmConfig = llmConfig;
        this.chatMemory = chatMemory;
        this.memoryAdvisor = chatMemory != null ? new MessageChatMemoryAdvisor(chatMemory) : null;
        this.modelCapabilityConfig = modelCapabilityConfig;
    }

    /**
     * 统一执行入口，按 ReAct 的思考 -> 执行 -> 观察节奏推进。
     */
    public final String execute(String input) {
        return execute(input, null, null);
    }

    /**
     * 统一执行入口，支持知识库ID。
     */
    public final String execute(String input, String kbId) {
        return execute(input, kbId, null);
    }

    /**
     * 统一执行入口，支持知识库ID和会话ID（用于短期记忆）。
     */
    public final String execute(String input, String kbId, String sessionId) {
        AgentExecutionContext context = new AgentExecutionContext(agentType, input);
        context.setKbId(kbId);
        context.setSessionId(sessionId);

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
     * 统一执行入口，支持 MultimodalContent 多模态输入。
     */
    public final String execute(MultimodalContent input, String kbId, String sessionId) {
        AgentExecutionContext context = new AgentExecutionContext(agentType, input.getTextOnly(), input);
        context.setKbId(kbId);
        context.setSessionId(sessionId);

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
     * 统一的 LLM 调用封装（不带记忆）。
     */
    protected String callLLM(String input) {
        return callLLM(input, null);
    }

    /**
     * 统一的 LLM 调用封装，支持短期记忆。
     */
    protected String callLLM(String input, String sessionId) {
        String prompt = input == null ? "" : input.trim();
        var spec = chatClient.prompt().user(prompt);

        if (memoryAdvisor != null && sessionId != null) {
            spec.advisors(memoryAdvisor)
                    .advisors(a -> a.param(
                            AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId));
        }

        return spec.call().content();
    }

    /**
     * 统一的 LLM 调用封装，支持多模态内容。
     */
    protected String callLLM(MultimodalContent input, String sessionId) {
        if (!input.hasImages()) {
            return callLLM(input.getTextOnly(), sessionId);
        }

        // Check if current model supports vision
        String currentModel = llmConfig != null ? llmConfig.getDefaultModel() : null;
        if (currentModel != null && modelCapabilityConfig != null && !modelCapabilityConfig.supportsVision(currentModel)) {
            // Model doesn't support vision - add hint in prompt
            int imageCount = input.getImageAttachments().size();
            String enhancedText = input.getTextOnly() +
                "\n\n[用户上传了 " + imageCount + " 张图片，但当前模型不支持图片理解。建议切换到支持视觉的模型（如 qwen-vl-plus）以获得更好的体验。]";
            return callLLM(enhancedText, sessionId);
        }

        // Model supports vision - build multimodal message
        try {
            List<Media> mediaList = input.getImageAttachments().stream()
                    .map(a -> {
                        try {
                            return new Media(
                                    MimeType.valueOf(a.getMimeType()),
                                    new java.net.URI(a.getFileUrl()).toURL());
                        } catch (Exception e) {
                            throw new RuntimeException("Invalid URL: " + a.getFileUrl(), e);
                        }
                    })
                    .toList();
            var spec = chatClient.prompt()
                    .user(u -> u.text(input.getTextOnly()).media(mediaList.toArray(new Media[0])));
            if (memoryAdvisor != null && sessionId != null) {
                spec.advisors(memoryAdvisor)
                        .advisors(a -> a.param(
                                AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId));
            }
            return spec.call().content();
        } catch (Exception e) {
            log.warn("多模态调用失败，降级为纯文本: {}", e.getMessage());
            return callLLM(input.getTextOnly(), sessionId);
        }
    }
}
