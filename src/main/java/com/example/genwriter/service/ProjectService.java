package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateProjectRequest;
import com.example.genwriter.model.dto.request.UpdateProjectRequest;
import com.example.genwriter.model.dto.response.ProjectDTO;

import java.util.List;

/**
 * 项目服务接口
 */
public interface ProjectService {

    /**
     * 创建项目
     *
     * @param request 创建请求
     * @return 项目DTO
     */
    ProjectDTO createProject(CreateProjectRequest request);

    /**
     * 根据ID查询项目
     *
     * @param id 项目ID
     * @return 项目DTO
     */
    ProjectDTO getProjectById(String id);

    /**
     * 查询所有项目
     *
     * @return 项目列表
     */
    List<ProjectDTO> getAllProjects();

    /**
     * 根据状态查询项目
     *
     * @param status 项目状态
     * @return 项目列表
     */
    List<ProjectDTO> getProjectsByStatus(String status);

    /**
     * 更新项目
     *
     * @param id 项目ID
     * @param request 更新请求
     * @return 更新后的项目DTO
     */
    ProjectDTO updateProject(String id, UpdateProjectRequest request);

    /**
     * 删除项目
     *
     * @param id 项目ID
     */
    void deleteProject(String id);

    /**
     * 批量删除项目
     *
     * @param ids 项目ID列表
     */
    void deleteProjects(List<String> ids);
}
