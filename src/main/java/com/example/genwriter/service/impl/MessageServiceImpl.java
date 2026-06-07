package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.MessageMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateMessageRequest;
import com.example.genwriter.model.dto.request.UpdateMessageRequest;
import com.example.genwriter.model.dto.response.MessageDTO;
import com.example.genwriter.model.entity.Message;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageMapper messageMapper;
    private final TaskSessionMapper taskSessionMapper;

    @Override
    @Transactional
    public MessageDTO createMessage(CreateMessageRequest request) {
        log.debug("创建消息: sessionId={}, role={}", request.getSessionId(), request.getRole());

        // 验证会话存在
        TaskSession session = taskSessionMapper.selectById(request.getSessionId());
        if (session == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }

        // 如果没有指定序号,自动获取下一个序号
        Integer sequence = request.getSequence();
        if (sequence == null) {
            sequence = messageMapper.getMaxSequenceBySessionId(request.getSessionId()) + 1;
        }

        Message message = Message.builder()
                .sessionId(request.getSessionId())
                .role(request.getRole())
                .type(StringUtils.hasText(request.getType()) ? request.getType() : "text")
                .content(request.getContent())
                .metadata(request.getMetadata())
                .parentId(request.getParentId())
                .sequence(sequence)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = messageMapper.insert(message);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(message);
    }

    @Override
    @Transactional(readOnly = true)
    public MessageDTO getMessageById(String id) {
        Message message = messageMapper.selectById(id);
        if (message == null) {
            throw new BizException(BizException.ErrorCode.MESSAGE_NOT_FOUND);
        }
        return convertToDTO(message);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageDTO> getMessagesBySessionId(String sessionId) {
        List<Message> messages = messageMapper.selectBySessionId(sessionId);
        return messages.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageDTO> getRecentMessages(String sessionId, int limit) {
        List<Message> messages = messageMapper.selectBySessionIdRecently(sessionId, limit);
        // 反转列表使其按时间正序排列
        return messages.stream()
                .sorted((a, b) -> a.getSequence().compareTo(b.getSequence()))
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<MessageDTO> getMessagesByRole(String sessionId, String role) {
        List<Message> messages = messageMapper.selectBySessionIdAndRole(sessionId, role);
        return messages.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MessageDTO updateMessage(String id, UpdateMessageRequest request) {
        log.debug("更新消息: {}", id);

        Message existing = messageMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.MESSAGE_NOT_FOUND);
        }

        Message message = Message.builder()
                .id(id)
                .role(request.getRole())
                .type(request.getType())
                .content(request.getContent())
                .metadata(request.getMetadata())
                .sequence(request.getSequence())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = messageMapper.updateById(message);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getMessageById(id);
    }

    @Override
    @Transactional
    public void deleteMessage(String id) {
        log.debug("删除消息: {}", id);

        Message existing = messageMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.MESSAGE_NOT_FOUND);
        }

        int result = messageMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public void deleteMessagesBySessionId(String sessionId) {
        log.debug("删除会话的所有消息: {}", sessionId);

        int result = messageMapper.deleteBySessionId(sessionId);
        log.debug("成功删除 {} 条消息", result);
    }

    @Override
    @Transactional(readOnly = true)
    public long countMessagesBySessionId(String sessionId) {
        return messageMapper.countBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void createMessage(String sessionId, String role, String content) {
        createMessage(sessionId, role, content, null);
    }

    @Override
    @Transactional
    public void createMessage(String sessionId, String role, String content, String metadata) {
        log.trace("创建消息: sessionId={}, role={}", sessionId, role);

        Integer sequence = messageMapper.getMaxSequenceBySessionId(sessionId) + 1;

        Message message = Message.builder()
                .sessionId(sessionId)
                .role(role)
                .type("text")
                .content(content)
                .metadata(metadata)
                .sequence(sequence)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        messageMapper.insert(message);
    }

    /**
     * 转换为DTO
     */
    private MessageDTO convertToDTO(Message message) {
        return MessageDTO.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .type(message.getType())
                .content(message.getContent())
                .metadata(message.getMetadata())
                .parentId(message.getParentId())
                .sequence(message.getSequence())
                .createdAt(message.getCreatedAt())
                .updatedAt(message.getUpdatedAt())
                .build();
    }
}
