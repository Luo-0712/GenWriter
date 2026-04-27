package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.DocumentMapper;
import com.example.genwriter.mapper.MessageMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.request.UpdateTaskSessionRequest;
import com.example.genwriter.model.dto.response.TaskSessionDTO;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.service.TaskSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSessionServiceImpl implements TaskSessionService {

    private final TaskSessionMapper taskSessionMapper;
    private final MessageMapper messageMapper;
    private final DocumentMapper documentMapper;

    @Override
    @Transactional
    public TaskSessionDTO createSession(CreateTaskSessionRequest request) {
        log.debug("创建任务会话: {}", request.getTitle());

        TaskSession session = TaskSession.builder()
                .title(request.getTitle())
                .type(StringUtils.hasText(request.getType()) ? request.getType() : "writing")
                .status("active")
                .topic(request.getTopic())
                .style(request.getStyle())
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = taskSessionMapper.insert(session);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(session);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskSessionDTO getSessionById(String id) {
        TaskSession session = taskSessionMapper.selectById(id);
        if (session == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }
        return convertToDTOWithStats(session);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskSessionDTO> getAllSessions() {
        List<TaskSession> sessions = taskSessionMapper.selectAll();
        return sessions.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskSessionDTO> getSessionsByStatus(String status) {
        List<TaskSession> sessions = taskSessionMapper.selectByStatus(status);
        return sessions.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskSessionDTO> getSessionsByType(String type) {
        List<TaskSession> sessions = taskSessionMapper.selectByType(type);
        return sessions.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TaskSessionDTO updateSession(String id, UpdateTaskSessionRequest request) {
        log.debug("更新任务会话: {}", id);

        TaskSession existing = taskSessionMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }

        TaskSession session = TaskSession.builder()
                .id(id)
                .title(request.getTitle())
                .type(request.getType())
                .status(request.getStatus())
                .topic(request.getTopic())
                .style(request.getStyle())
                .metadata(request.getMetadata())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = taskSessionMapper.updateById(session);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getSessionById(id);
    }

    @Override
    @Transactional
    public void deleteSession(String id) {
        log.debug("删除任务会话: {}", id);

        TaskSession existing = taskSessionMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }

        int result = taskSessionMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public void deleteSessions(List<String> ids) {
        log.debug("批量删除任务会话: {}", ids);

        if (ids == null || ids.isEmpty()) {
            return;
        }

        int result = taskSessionMapper.deleteByIds(ids);
        log.debug("成功删除 {} 个会话", result);
    }

    @Override
    @Transactional
    public TaskSessionDTO updateSessionStatus(String id, String status) {
        log.debug("更新会话状态: {} -> {}", id, status);

        TaskSession existing = taskSessionMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }

        TaskSession session = TaskSession.builder()
                .id(id)
                .status(status)
                .updatedAt(LocalDateTime.now())
                .build();

        int result = taskSessionMapper.updateById(session);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getSessionById(id);
    }

    /**
     * 转换为DTO
     */
    private TaskSessionDTO convertToDTO(TaskSession session) {
        return TaskSessionDTO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .type(session.getType())
                .status(session.getStatus())
                .topic(session.getTopic())
                .style(session.getStyle())
                .metadata(session.getMetadata())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    /**
     * 转换为DTO(包含统计信息)
     */
    private TaskSessionDTO convertToDTOWithStats(TaskSession session) {
        TaskSessionDTO dto = convertToDTO(session);
        dto.setMessageCount(messageMapper.countBySessionId(session.getId()));
        dto.setDocumentCount(documentMapper.countBySessionId(session.getId()));
        return dto;
    }
}
