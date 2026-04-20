package com.example.genwriter.agent;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.chatclient.ChatClientRegistry;
import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 配置类
 * 创建所有 Agent Bean，并将对应的 ChatClient 注册到 ChatClientRegistry
 * 使用 Spring AI Alibaba 提供的 ChatModel
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final ChatClientFactory chatClientFactory;
    private final ChatClientRegistry chatClientRegistry;
    private final LLMConfig llmConfig;

    @Bean
    public WritingAgent writingAgent() {
        WritingAgent agent = new WritingAgent(
                chatClientFactory.createWithSystemPrompt(llmConfig.getPrompts().getWritingSystemPrompt()),
                llmConfig
        );
        chatClientRegistry.register(AgentType.WRITING, agent.getChatClient());
        chatClientRegistry.register("writing", agent.getChatClient());
        log.info("WritingAgent 初始化完成");
        return agent;
    }

    @Bean
    public OutlineAgent outlineAgent() {
        OutlineAgent agent = new OutlineAgent(
                chatClientFactory.createWithSystemPrompt("你是一位专业的大纲生成助手，擅长根据用户需求生成清晰、层次分明的文档大纲。"),
                llmConfig
        );
        chatClientRegistry.register(AgentType.OUTLINE, agent.getChatClient());
        chatClientRegistry.register("outline", agent.getChatClient());
        log.info("OutlineAgent 初始化完成");
        return agent;
    }

    @Bean
    public PolishAgent polishAgent() {
        PolishAgent agent = new PolishAgent(
                chatClientFactory.createWithSystemPrompt(llmConfig.getPrompts().getDocumentPolishPrompt()),
                llmConfig
        );
        chatClientRegistry.register(AgentType.POLISH, agent.getChatClient());
        chatClientRegistry.register("polish", agent.getChatClient());
        log.info("PolishAgent 初始化完成");
        return agent;
    }

    @Bean
    public KnowledgeAgent knowledgeAgent() {
        KnowledgeAgent agent = new KnowledgeAgent(
                chatClientFactory.createWithSystemPrompt(llmConfig.getPrompts().getKnowledgeQaPrompt()),
                llmConfig
        );
        chatClientRegistry.register(AgentType.KNOWLEDGE, agent.getChatClient());
        chatClientRegistry.register("knowledge", agent.getChatClient());
        log.info("KnowledgeAgent 初始化完成");
        return agent;
    }
}
