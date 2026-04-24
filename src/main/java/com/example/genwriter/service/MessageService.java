package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateMessageRequest;
import com.example.genwriter.model.dto.request.UpdateMessageRequest;
import com.example.genwriter.model.dto.response.MessageDTO;

import java.util.List;

/**
 * 消息服务接口
 */
public interface MessageService {

    /**
     * 创建消息
     *
     * @param request 创建请求
     * @return 消息DTO
     */
    MessageDTO createMessage(CreateMessageRequest request);

    /**
     * 根据ID查询消息
     *
     * @param id 消息ID
     * @return 消息DTO
     */
    MessageDTO getMessageById(String id);

    /**
     * 根据会话ID查询消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<MessageDTO> getMessagesBySessionId(String sessionId);

    /**
     * 查询会话的最近N条消息
     *
     * @param sessionId 会话ID
     * @param limit 限制数量
     * @return 消息列表
     */
    List<MessageDTO> getRecentMessages(String sessionId, int limit);

    /**
     * 根据角色查询消息
     *
     * @param sessionId 会话ID
     * @param role 消息角色
     * @return 消息列表
     */
    List<MessageDTO> getMessagesByRole(String sessionId, String role);

    /**
     * 更新消息
     *
     * @param id 消息ID
     * @param request 更新请求
     * @return 更新后的消息DTO
     */
    MessageDTO updateMessage(String id, UpdateMessageRequest request);

    /**
     * 删除消息
     *
     * @param id 消息ID
     */
    void deleteMessage(String id);

    /**
     * 删除会话的所有消息
     *
     * @param sessionId 会话ID
     */
    void deleteMessagesBySessionId(String sessionId);

    /**
     * 获取会话中的消息数量
     *
     * @param sessionId 会话ID
     * @return 消息数量
     */
    long countMessagesBySessionId(String sessionId);

    /**
     * 创建消息（简化版）
     *
     * @param sessionId 会话ID
     * @param role 消息角色
     * @param content 消息内容
     */
    void createMessage(String sessionId, String role, String content);
}
