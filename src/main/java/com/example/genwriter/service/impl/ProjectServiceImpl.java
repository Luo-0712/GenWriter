package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.ProjectMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateProjectRequest;
import com.example.genwriter.model.dto.request.UpdateProjectRequest;
import com.example.genwriter.model.dto.response.ProjectDTO;
import com.example.genwriter.model.entity.Project;
import com.example.genwriter.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectMapper projectMapper;
    private final TaskSessionMapper taskSessionMapper;

    @Override
    @Transactional
    public ProjectDTO createProject(CreateProjectRequest request) {
        log.debug("创建项目: {}", request.getName());

        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .status("active")
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = projectMapper.insert(project);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(project);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectDTO getProjectById(String id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BizException(BizException.ErrorCode.PROJECT_NOT_FOUND);
        }
        return convertToDTOWithStats(project);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectDTO> getAllProjects() {
        List<Project> projects = projectMapper.selectAll();
        return projects.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectDTO> getProjectsByStatus(String status) {
        List<Project> projects = projectMapper.selectByStatus(status);
        return projects.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProjectDTO updateProject(String id, UpdateProjectRequest request) {
        log.debug("更新项目: {}", id);

        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.PROJECT_NOT_FOUND);
        }

        Project project = Project.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .status(request.getStatus())
                .metadata(request.getMetadata())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = projectMapper.updateById(project);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getProjectById(id);
    }

    @Override
    @Transactional
    public void deleteProject(String id) {
        log.debug("删除项目: {}", id);

        Project existing = projectMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.PROJECT_NOT_FOUND);
        }

        int result = projectMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public void deleteProjects(List<String> ids) {
        log.debug("批量删除项目: {}", ids);

        if (ids == null || ids.isEmpty()) {
            return;
        }

        int result = projectMapper.deleteByIds(ids);
        log.debug("成功删除 {} 个项目", result);
    }

    /**
     * 转换为DTO
     */
    private ProjectDTO convertToDTO(Project project) {
        return ProjectDTO.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .status(project.getStatus())
                .metadata(project.getMetadata())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    /**
     * 转换为DTO(包含统计信息)
     */
    private ProjectDTO convertToDTOWithStats(Project project) {
        ProjectDTO dto = convertToDTO(project);
        dto.setSessionCount(taskSessionMapper.countByProjectId(project.getId()));
        return dto;
    }
}
