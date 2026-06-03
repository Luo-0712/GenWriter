package com.example.genwriter.controller;

import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateMessageRequest;
import com.example.genwriter.model.dto.request.UpdateMessageRequest;
import com.example.genwriter.model.dto.response.MessageDTO;
import com.example.genwriter.model.vo.MessageVO;
import com.example.genwriter.service.MessageService;
import com.example.genwriter.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final ChatService chatService;

    /**
     * 创建消息
     */
    @PostMapping
    public ApiResponse<MessageVO> createMessage(@Valid @RequestBody CreateMessageRequest request) {
        log.debug("创建消息请求：sessionId={}", request.getSessionId());
        MessageDTO dto = messageService.createMessage(request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 发送消息并触发 AI 响应（SSE 流式模式）
     * 前端需要先建立 SSE 连接：GET /sse/connect/{sessionId}
     * 然后调用此接口触发 AI 响应，结果会通过 SSE 推送
     */
    @PostMapping("/{sessionId}/chat")
    public ApiResponse<Void> chat(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "AUTO") ChatEvent.WritingType type,
            @RequestParam(defaultValue = "true") boolean webSearch,
            @RequestParam(required = false) String kbId,
            @RequestBody(required = false) String userInput) {
        log.debug("聊天请求：sessionId={}, type={}, webSearch={}, kbId={}", sessionId, type, webSearch, kbId);
        // 发布聊天事件，由 @EventListener 异步处理并通过 SSE 推送结果
        chatService.submitChatTask(sessionId, userInput, type, webSearch, kbId);
        return ApiResponse.success(null);
    }

    /**
     * 根据 ID 查询消息
     */
    @GetMapping("/{id}")
    public ApiResponse<MessageVO> getMessage(@PathVariable String id) {
        log.debug("查询消息：{}", id);
        MessageDTO dto = messageService.getMessageById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 根据会话 ID 查询消息列表
     */
    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<MessageVO>> getMessagesBySessionId(@PathVariable String sessionId) {
        log.debug("查询会话消息：{}", sessionId);
        List<MessageDTO> dtos = messageService.getMessagesBySessionId(sessionId);
        List<MessageVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 查询会话的最近 N 条消息
     */
    @GetMapping("/session/{sessionId}/recent")
    public ApiResponse<List<MessageVO>> getRecentMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "10") int limit) {
        log.debug("查询会话最近消息：{}, limit={}", sessionId, limit);
        List<MessageDTO> dtos = messageService.getRecentMessages(sessionId, limit);
        List<MessageVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 根据角色查询消息
     */
    @GetMapping("/session/{sessionId}/role/{role}")
    public ApiResponse<List<MessageVO>> getMessagesByRole(
            @PathVariable String sessionId,
            @PathVariable String role) {
        log.debug("查询会话角色消息：{}, role={}", sessionId, role);
        List<MessageDTO> dtos = messageService.getMessagesByRole(sessionId, role);
        List<MessageVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 更新消息
     */
    @PutMapping("/{id}")
    public ApiResponse<MessageVO> updateMessage(
            @PathVariable String id,
            @Valid @RequestBody UpdateMessageRequest request) {
        log.debug("更新消息：{}", id);
        MessageDTO dto = messageService.updateMessage(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 删除消息
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMessage(@PathVariable String id) {
        log.debug("删除消息：{}", id);
        messageService.deleteMessage(id);
        return ApiResponse.success(null);
    }

    /**
     * 删除会话的所有消息
     */
    @DeleteMapping("/session/{sessionId}")
    public ApiResponse<Void> deleteMessagesBySessionId(@PathVariable String sessionId) {
        log.debug("删除会话的所有消息：{}", sessionId);
        messageService.deleteMessagesBySessionId(sessionId);
        return ApiResponse.success(null);
    }

    /**
     * 获取会话消息数量
     */
    @GetMapping("/session/{sessionId}/count")
    public ApiResponse<Long> countMessagesBySessionId(@PathVariable String sessionId) {
        log.debug("统计会话消息数量：{}", sessionId);
        long count = messageService.countMessagesBySessionId(sessionId);
        return ApiResponse.success(count);
    }

    /**
     * DTO 转换为 VO
     */
    private MessageVO convertToVO(MessageDTO dto) {
        return MessageVO.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .role(dto.getRole())
                .type(dto.getType())
                .content(dto.getContent())
                .metadata(parseMetadata(dto.getMetadata()))
                .parentId(dto.getParentId())
                .sequence(dto.getSequence())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    /**
     * 解析元数据 JSON
     */
    private Object parseMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadata, Object.class);
        } catch (Exception e) {
            return metadata;
        }
    }
}
