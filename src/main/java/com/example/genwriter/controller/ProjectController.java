package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateProjectRequest;
import com.example.genwriter.model.dto.request.UpdateProjectRequest;
import com.example.genwriter.model.dto.response.ProjectDTO;
import com.example.genwriter.model.vo.ProjectVO;
import com.example.genwriter.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 创建项目
     */
    @PostMapping
    public ApiResponse<ProjectVO> createProject(@Valid @RequestBody CreateProjectRequest request) {
        log.debug("创建项目请求: {}", request.getName());
        ProjectDTO dto = projectService.createProject(request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 根据ID查询项目
     */
    @GetMapping("/{id}")
    public ApiResponse<ProjectVO> getProject(@PathVariable String id) {
        log.debug("查询项目: {}", id);
        ProjectDTO dto = projectService.getProjectById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 查询所有项目
     */
    @GetMapping
    public ApiResponse<List<ProjectVO>> getAllProjects() {
        log.debug("查询所有项目");
        List<ProjectDTO> dtos = projectService.getAllProjects();
        List<ProjectVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 根据状态查询项目
     */
    @GetMapping("/status/{status}")
    public ApiResponse<List<ProjectVO>> getProjectsByStatus(@PathVariable String status) {
        log.debug("根据状态查询项目: {}", status);
        List<ProjectDTO> dtos = projectService.getProjectsByStatus(status);
        List<ProjectVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    /**
     * 更新项目
     */
    @PutMapping("/{id}")
    public ApiResponse<ProjectVO> updateProject(
            @PathVariable String id,
            @Valid @RequestBody UpdateProjectRequest request) {
        log.debug("更新项目: {}", id);
        ProjectDTO dto = projectService.updateProject(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteProject(@PathVariable String id) {
        log.debug("删除项目: {}", id);
        projectService.deleteProject(id);
        return ApiResponse.success(null);
    }

    /**
     * 批量删除项目
     */
    @DeleteMapping
    public ApiResponse<Void> deleteProjects(@RequestBody List<String> ids) {
        log.debug("批量删除项目: {}", ids);
        projectService.deleteProjects(ids);
        return ApiResponse.success(null);
    }

    /**
     * DTO转换为VO
     */
    private ProjectVO convertToVO(ProjectDTO dto) {
        return ProjectVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .status(dto.getStatus())
                .metadata(parseMetadata(dto.getMetadata()))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .sessionCount(dto.getSessionCount())
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
