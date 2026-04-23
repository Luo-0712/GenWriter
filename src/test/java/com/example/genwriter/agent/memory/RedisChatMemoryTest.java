package com.example.genwriter.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

/**
 * RedisChatMemory 单元测试
 * 验证序列化、消息读写、裁剪与清理逻辑
 */
@ExtendWith(MockitoExtension.class)
class RedisChatMemoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @InjectMocks
    private RedisChatMemory redisChatMemory;

    private ObjectMapper objectMapper;
    private MemoryProperties properties;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new MemoryProperties();
        properties.setKeyPrefix("genwriter:chat:memory:");
        properties.setTtlHours(24);
        properties.setWindowSize(20);
        properties.setMaxMessages(50);

        redisChatMemory = new RedisChatMemory(redisTemplate, objectMapper, properties);

        // Redis List 操作的统一 mock（lenient 避免未使用报错）
        lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
    }

    @Test
    void addUserMessage_ShouldSerializeAndPushToRedis() {
        // Given
        String sessionId = "session-001";
        UserMessage message = new UserMessage("你好，请帮我写一篇文章");

        // When
        redisChatMemory.add(sessionId, Collections.singletonList(message));

        // Then
        verify(listOps, times(1)).rightPush(eq("genwriter:chat:memory:session-001"), contains("你好，请帮我写一篇文章"));
        verify(redisTemplate, times(1)).expire(eq("genwriter:chat:memory:session-001"), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    void addAssistantMessage_WithToolCalls_ShouldPreserveToolCalls() {
        // Given
        String sessionId = "session-002";
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "search", "{\"query\":\"test\"}");
        AssistantMessage message = new AssistantMessage("我来搜索一下", Collections.emptyMap(), Collections.singletonList(toolCall));

        // When
        redisChatMemory.add(sessionId, Collections.singletonList(message));

        // Then
        verify(listOps, times(1)).rightPush(eq("genwriter:chat:memory:session-002"), contains("search"));
    }

    @Test
    void getMessages_ShouldReturnLastNMessages() {
        // Given
        String sessionId = "session-003";
        String json1 = "{\"messageType\":\"user\",\"content\":\"第一条\"}";
        String json2 = "{\"messageType\":\"assistant\",\"content\":\"回复1\"}";
        String json3 = "{\"messageType\":\"user\",\"content\":\"第二条\"}";

        when(listOps.size("genwriter:chat:memory:session-003")).thenReturn(3L);
        when(listOps.range("genwriter:chat:memory:session-003", 1, 2))
                .thenReturn(Arrays.asList(json2, json3));

        // When
        List<Message> result = redisChatMemory.get(sessionId, 2);

        // Then
        assertEquals(2, result.size());
        assertEquals("回复1", result.get(0).getContent());
        assertEquals("第二条", result.get(1).getContent());
    }

    @Test
    void getAllMessages_ShouldReturnAllMessagesInOrder() {
        // Given
        String sessionId = "session-004";
        String json1 = "{\"messageType\":\"system\",\"content\":\"系统提示\"}";
        String json2 = "{\"messageType\":\"user\",\"content\":\"用户问题\"}";

        when(listOps.range("genwriter:chat:memory:session-004", 0, -1))
                .thenReturn(Arrays.asList(json1, json2));

        // When
        List<Message> result = redisChatMemory.getAllMessages(sessionId);

        // Then
        assertEquals(2, result.size());
        assertTrue(result.get(0) instanceof SystemMessage);
        assertTrue(result.get(1) instanceof UserMessage);
    }

    @Test
    void addMessages_ExceedMaxSize_ShouldTrim() {
        // Given
        String sessionId = "session-005";
        properties.setMaxMessages(3);
        UserMessage message = new UserMessage("新消息");

        when(listOps.size("genwriter:chat:memory:session-005")).thenReturn(5L);

        // When
        redisChatMemory.add(sessionId, Collections.singletonList(message));

        // Then
        verify(listOps, times(1)).trim("genwriter:chat:memory:session-005", 2, 4);
    }

    @Test
    void clear_ShouldDeleteRedisKey() {
        // Given
        String sessionId = "session-006";

        // When
        redisChatMemory.clear(sessionId);

        // Then
        verify(redisTemplate, times(1)).delete("genwriter:chat:memory:session-006");
    }

    @Test
    void countMessages_ShouldReturnSize() {
        // Given
        String sessionId = "session-007";
        when(listOps.size("genwriter:chat:memory:session-007")).thenReturn(10L);

        // When
        long count = redisChatMemory.countMessages(sessionId);

        // Then
        assertEquals(10, count);
    }

    @Test
    void addNullOrEmptyMessages_ShouldDoNothing() {
        // When
        redisChatMemory.add("session-008", Collections.emptyList());

        // Then
        verify(listOps, never()).rightPush(anyString(), anyString());
    }
}
