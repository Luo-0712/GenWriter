package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.request.UpdateTaskSessionRequest;
import com.example.genwriter.model.dto.response.TaskSessionDTO;
import com.example.genwriter.model.vo.TaskSessionVO;
import com.example.genwriter.service.TaskSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务会话控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class TaskSessionController {

    private final TaskSessionService taskSessionService;

    /**
     * 创建会话
     */
    @PostMapping
    public ApiResponse<TaskSessionVO> createSession(@Valid @RequestBody CreateTaskSessionRequest request) {
        log.debug("创建会话请求: {}", request.getTitle());
        TaskSessionDTO dto = taskSessionService.createSession(request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 根据ID查询会话
     */
    @GetMapping("/{id}")
    public ApiResponse<TaskSessionVO> getSession(@PathVariable String id) {
        log.debug("查询会话: {}", id);
        TaskSessionDTO dto = taskSessionService.getSessionById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 查询所有会话
     */
    @GetMapping
    public ApiResponse<List<TaskSessionVO>> getAllSessions() {
        log.debug("查询所有会话");
        List<TaskSessionDTO> dtos = taskSessionService.getAllSessions();
        List<TaskSessionVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 根据状态查询会话
     */
    @GetMapping("/status/{status}")
    public ApiResponse<List<TaskSessionVO>> getSessionsByStatus(@PathVariable String status) {
        log.debug("根据状态查询会话: {}", status);
        List<TaskSessionDTO> dtos = taskSessionService.getSessionsByStatus(status);
        List<TaskSessionVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 根据类型查询会话
     */
    @GetMapping("/type/{type}")
    public ApiResponse<List<TaskSessionVO>> getSessionsByType(@PathVariable String type) {
        log.debug("根据类型查询会话: {}", type);
        List<TaskSessionDTO> dtos = taskSessionService.getSessionsByType(type);
        List<TaskSessionVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 更新会话
     */
    @PutMapping("/{id}")
    public ApiResponse<TaskSessionVO> updateSession(
            @PathVariable String id,
            @Valid @RequestBody UpdateTaskSessionRequest request) {
        log.debug("更新会话: {}", id);
        TaskSessionDTO dto = taskSessionService.updateSession(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSession(@PathVariable String id) {
        log.debug("删除会话: {}", id);
        taskSessionService.deleteSession(id);
        return ApiResponse.success(null);
    }

    /**
     * 批量删除会话
     */
    @DeleteMapping
    public ApiResponse<Void> deleteSessions(@RequestBody List<String> ids) {
        log.debug("批量删除会话: {}", ids);
        taskSessionService.deleteSessions(ids);
        return ApiResponse.success(null);
    }

    /**
     * 更新会话状态
     */
    @PatchMapping("/{id}/status")
    public ApiResponse<TaskSessionVO> updateSessionStatus(
            @PathVariable String id,
            @RequestParam String status) {
        log.debug("更新会话状态: {} -> {}", id, status);
        TaskSessionDTO dto = taskSessionService.updateSessionStatus(id, status);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * DTO转换为VO
     */
    private TaskSessionVO convertToVO(TaskSessionDTO dto) {
        return TaskSessionVO.builder()
                .id(dto.getId())
                .title(dto.getTitle())
                .type(dto.getType())
                .status(dto.getStatus())
                .topic(dto.getTopic())
                .style(dto.getStyle())
                .metadata(parseMetadata(dto.getMetadata()))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .messageCount(dto.getMessageCount())
                .documentCount(dto.getDocumentCount())
                .build();
    }

    /**
     * 解析元数据JSON
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
