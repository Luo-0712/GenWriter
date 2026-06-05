package com.example.genwriter.agent.chatclient;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChatClient 注册表
 * 管理所有已注册的 ChatClient 实例
 */
@Slf4j
@Component
public class ChatClientRegistry {

    private final ConcurrentHashMap<String, ChatClient> clientsByName = new ConcurrentHashMap<>();

    /**
     * 注册 ChatClient
     */
    public void register(String name, ChatClient client) {
        clientsByName.put(name, client);
        log.debug("ChatClient 已注册: {}", name);
    }

    /**
     * 获取 ChatClient
     */
    public ChatClient get(String name) {
        return clientsByName.get(name);
    }

    /**
     * 检查是否已注册
     */
    public boolean isRegistered(String name) {
        return clientsByName.containsKey(name);
    }

    /**
     * 获取所有已注册的名称
     */
    public Set<String> getRegisteredNames() {
        return Collections.unmodifiableSet(clientsByName.keySet());
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        clientsByName.clear();
        log.debug("所有 ChatClient 已注销");
    }
}
