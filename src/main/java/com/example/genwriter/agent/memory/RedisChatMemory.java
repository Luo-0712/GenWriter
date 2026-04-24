package com.example.genwriter.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的 ChatMemory 实现
 * 为 Spring AI ChatClient 提供短期记忆持久化支持
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemory implements ChatMemory {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String key = buildKey(conversationId);
        for (Message message : messages) {
            String json = serializeMessage(message);
            if (json != null) {
                redisTemplate.opsForList().rightPush(key, json);
            }
        }

        // 限制最大消息数并设置过期时间
        trimMessages(key, properties.getMaxMessages());
        redisTemplate.expire(key, properties.getTtlHours(), TimeUnit.HOURS);

        log.debug("Added {} messages to Redis memory for conversation: {}", messages.size(), conversationId);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = buildKey(conversationId);
        Long size = redisTemplate.opsForList().size(key);
        if (size == null || size == 0) {
            return Collections.emptyList();
        }

        int start = (int) Math.max(0, size - lastN);
        List<String> jsonList = redisTemplate.opsForList().range(key, start, size - 1);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }

        return jsonList.stream()
                .map(this::deserializeMessage)
                .filter(msg -> msg != null)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
        String key = buildKey(conversationId);
        redisTemplate.delete(key);
        log.debug("Cleared Redis memory for conversation: {}", conversationId);
    }

    private String buildKey(String conversationId) {
        return properties.getKeyPrefix() + conversationId;
    }

    /**
     * 获取指定会话的所有记忆消息
     */
    public List<Message> getAllMessages(String conversationId) {
        String key = buildKey(conversationId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();
        }
        return jsonList.stream()
                .map(this::deserializeMessage)
                .filter(msg -> msg != null)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定会话的记忆消息数量
     */
    public long countMessages(String conversationId) {
        String key = buildKey(conversationId);
        Long size = redisTemplate.opsForList().size(key);
        return size != null ? size : 0;
    }

    /**
     * 获取指定会话的记忆在 Redis 中的剩余存活时间（秒）
     */
    public long getTtl(String conversationId) {
        String key = buildKey(conversationId);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null ? ttl : -2;
    }

    private void trimMessages(String key, int maxSize) {
        Long size = redisTemplate.opsForList().size(key);
        if (size != null && size > maxSize) {
            redisTemplate.opsForList().trim(key, size - maxSize, size - 1);
        }
    }

    private String serializeMessage(Message message) {
        try {
            ChatMessageRecord record = new ChatMessageRecord();
            record.setMessageType(message.getMessageType().getValue());
            record.setContent(message.getText());
            record.setMetadata(message.getMetadata());

            if (message instanceof AssistantMessage assistantMessage) {
                if (assistantMessage.getToolCalls() != null) {
                    record.setToolCalls(assistantMessage.getToolCalls().stream()
                            .map(tc -> new ToolCallRecord(tc.id(), tc.type(), tc.name(), tc.arguments()))
                            .collect(Collectors.toList()));
                }
            }

            if (message instanceof ToolResponseMessage toolResponseMessage) {
                if (toolResponseMessage.getResponses() != null) {
                    record.setToolResponses(toolResponseMessage.getResponses().stream()
                            .map(tr -> new ToolResponseRecord(tr.id(), tr.name(), tr.responseData()))
                            .collect(Collectors.toList()));
                }
            }

            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message: {}", message, e);
            return null;
        }
    }

    private Message deserializeMessage(String json) {
        try {
            ChatMessageRecord record = objectMapper.readValue(json, ChatMessageRecord.class);
            MessageType type = MessageType.fromValue(record.getMessageType());

            return switch (type) {
                case USER -> new UserMessage(record.getContent());
                case SYSTEM -> new SystemMessage(record.getContent());
                case ASSISTANT -> {
                    List<AssistantMessage.ToolCall> toolCalls = Collections.emptyList();
                    if (record.getToolCalls() != null) {
                        toolCalls = record.getToolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(tc.getId(), tc.getType(), tc.getName(), tc.getArguments()))
                                .collect(Collectors.toList());
                    }
                    Map<String, Object> metadata = record.getMetadata() != null ? record.getMetadata() : Collections.emptyMap();
                    yield new AssistantMessage(record.getContent(), metadata, toolCalls);
                }
                case TOOL -> {
                    List<ToolResponseMessage.ToolResponse> responses = null;
                    if (record.getToolResponses() != null) {
                        responses = record.getToolResponses().stream()
                                .map(tr -> new ToolResponseMessage.ToolResponse(tr.getId(), tr.getName(), tr.getResponseData()))
                                .collect(Collectors.toList());
                    }
                    Map<String, Object> metadata = record.getMetadata() != null ? record.getMetadata() : Collections.emptyMap();
                    yield new ToolResponseMessage(responses != null ? responses : Collections.emptyList(), metadata);
                }
            };
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize message: {}", json, e);
            return null;
        }
    }

    /**
     * 内部 DTO，用于 JSON 序列化
     */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    private static class ChatMessageRecord {
        private String messageType;
        private String content;
        private Map<String, Object> metadata;
        private List<ToolCallRecord> toolCalls;
        private List<ToolResponseRecord> toolResponses;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    private static class ToolCallRecord {
        private String id;
        private String type;
        private String name;
        private String arguments;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    private static class ToolResponseRecord {
        private String id;
        private String name;
        private String responseData;
    }
}
