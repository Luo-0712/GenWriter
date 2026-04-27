package com.example.genwriter.agent.chatclient;

import com.example.genwriter.agent.AgentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 注册表
 * 管理所有已注册的 ChatClient 实例，按 Agent 类型分组
 */
@Slf4j
@Component
public class ChatClientRegistry {

    /**
     * 按名称注册的 ChatClient
     */
    private final ConcurrentHashMap<String, ChatClient> clientsByName;

    /**
     * 按 Agent 类型分组的 ChatClient
     */
    private final ConcurrentHashMap<AgentType, ChatClient> clientsByType;

    public ChatClientRegistry() {
        this.clientsByName = new ConcurrentHashMap<>();
        this.clientsByType = new ConcurrentHashMap<>();
    }

    /**
     * 注册 ChatClient（按名称）
     * @param name 名称
     * @param client ChatClient 实例
     */
    public void register(String name, ChatClient client) {
        clientsByName.put(name, client);
        log.debug("ChatClient 已注册: {}", name);
    }

    /**
     * 注册 ChatClient（按 Agent 类型）
     * @param agentType Agent 类型
     * @param client ChatClient 实例
     */
    public void register(AgentType agentType, ChatClient client) {
        clientsByType.put(agentType, client);
        log.debug("ChatClient 已注册 (类型: {})", agentType);
    }

    /**
     * 注销 ChatClient
     * @param name 名称
     * @return 是否注销成功
     */
    public boolean unregister(String name) {
        return clientsByName.remove(name) != null;
    }

    /**
     * 根据名称获取 ChatClient
     * @param name 名称
     * @return ChatClient 实例
     */
    public ChatClient getByName(String name) {
        return clientsByName.get(name);
    }

    /**
     * 根据 Agent 类型获取 ChatClient
     * @param agentType Agent 类型
     * @return ChatClient 实例
     */
    public ChatClient getByType(AgentType agentType) {
        return clientsByType.get(agentType);
    }

    /**
     * 检查是否已注册
     * @param name 名称
     * @return 是否已注册
     */
    public boolean isRegistered(String name) {
        return clientsByName.containsKey(name);
    }

    /**
     * 获取所有已注册的名称
     * @return 名称集合
     */
    public Set<String> getRegisteredNames() {
        return Collections.unmodifiableSet(clientsByName.keySet());
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        clientsByName.clear();
        clientsByType.clear();
        log.debug("所有 ChatClient 已注销");
    }
}
