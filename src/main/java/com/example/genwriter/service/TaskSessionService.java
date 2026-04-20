package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.request.UpdateTaskSessionRequest;
import com.example.genwriter.model.dto.response.TaskSessionDTO;

import java.util.List;

/**
 * 任务会话服务接口
 */
public interface TaskSessionService {

    /**
     * 创建会话
     *
     * @param request 创建请求
     * @return 会话DTO
     */
    TaskSessionDTO createSession(CreateTaskSessionRequest request);

    /**
     * 根据ID查询会话
     *
     * @param id 会话ID
     * @return 会话DTO
     */
    TaskSessionDTO getSessionById(String id);

    /**
     * 查询所有会话
     *
     * @return 会话列表
     */
    List<TaskSessionDTO> getAllSessions();

    /**
     * 根据状态查询会话
     *
     * @param status 会话状态
     * @return 会话列表
     */
    List<TaskSessionDTO> getSessionsByStatus(String status);

    /**
     * 根据类型查询会话
     *
     * @param type 会话类型
     * @return 会话列表
     */
    List<TaskSessionDTO> getSessionsByType(String type);

    /**
     * 更新会话
     *
     * @param id 会话ID
     * @param request 更新请求
     * @return 更新后的会话DTO
     */
    TaskSessionDTO updateSession(String id, UpdateTaskSessionRequest request);

    /**
     * 删除会话
     *
     * @param id 会话ID
     */
    void deleteSession(String id);

    /**
     * 批量删除会话
     *
     * @param ids 会话ID列表
     */
    void deleteSessions(List<String> ids);

    /**
     * 更新会话状态
     *
     * @param id 会话ID
     * @param status 新状态
     * @return 更新后的会话DTO
     */
    TaskSessionDTO updateSessionStatus(String id, String status);
}
