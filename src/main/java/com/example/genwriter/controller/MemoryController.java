package com.example.genwriter.controller;

import com.example.genwriter.agent.memory.MemoryProperties;
import com.example.genwriter.agent.memory.RedisChatMemory;
import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateMemoryMessageRequest;
import com.example.genwriter.model.vo.MemoryMessageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 短期记忆管理控制器
 * 提供查看、清除会话 Redis 记忆的调试接口
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final RedisChatMemory redisChatMemory;
    private final MemoryProperties memoryProperties;

    /**
     * 获取指定会话的所有记忆消息
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<List<MemoryMessageVO>> getMemoryMessages(@PathVariable String sessionId) {
        log.info("查询会话记忆: sessionId={}", sessionId);
        List<Message> messages = redisChatMemory.getAllMessages(sessionId);
        List<MemoryMessageVO> vos = messages.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 获取指定会话的记忆统计信息
     */
    @GetMapping("/{sessionId}/stats")
    public ApiResponse<Map<String, Object>> getMemoryStats(@PathVariable String sessionId) {
        log.info("查询会话记忆统计: sessionId={}", sessionId);
        long count = redisChatMemory.countMessages(sessionId);
        long ttl = redisChatMemory.getTtl(sessionId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("sessionId", sessionId);
        stats.put("messageCount", count);
        stats.put("ttlSeconds", ttl);
        stats.put("ttlHours", ttl > 0 ? String.format("%.2f", ttl / 3600.0) : "expired");
        stats.put("keyPrefix", memoryProperties.getKeyPrefix());
        stats.put("windowSize", memoryProperties.getWindowSize());
        stats.put("maxMessages", memoryProperties.getMaxMessages());

        return ApiResponse.success(stats);
    }

    /**
     * 手动添加消息到指定会话记忆
     */
    @PostMapping("/{sessionId}")
    public ApiResponse<Void> addMemoryMessage(@PathVariable String sessionId,
                                              @Valid @RequestBody CreateMemoryMessageRequest request) {
        log.info("手动添加记忆消息: sessionId={}, role={}", sessionId, request.getRole());

        Message message = switch (request.getRole().toLowerCase()) {
            case "user" -> new UserMessage(request.getContent());
            case "assistant" -> new AssistantMessage(request.getContent(), request.getMetadata(), null);
            case "system" -> new SystemMessage(request.getContent());
            default -> throw new IllegalArgumentException("不支持的消息角色: " + request.getRole());
        };

        redisChatMemory.add(sessionId, message);
        return ApiResponse.success("消息已添加", null);
    }

    /**
     * 清除指定会话的记忆
     */
    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> clearMemory(@PathVariable String sessionId) {
        log.info("清除会话记忆: sessionId={}", sessionId);
        redisChatMemory.clear(sessionId);
        return ApiResponse.success("记忆已清除", null);
    }

    private MemoryMessageVO convertToVO(Message message) {
        boolean hasToolCalls = false;
        boolean hasToolResponses = false;

        if (message instanceof org.springframework.ai.chat.messages.AssistantMessage am) {
            hasToolCalls = am.getToolCalls() != null && !am.getToolCalls().isEmpty();
        }
        if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
            hasToolResponses = trm.getResponses() != null && !trm.getResponses().isEmpty();
        }

        return MemoryMessageVO.builder()
                .role(message.getMessageType().getValue())
                .content(message.getContent())
                .metadata(message.getMetadata())
                .hasToolCalls(hasToolCalls)
                .hasToolResponses(hasToolResponses)
                .build();
    }
}
