package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 记忆消息视图对象
 * 用于返回 Redis 中存储的短期记忆内容
 */
@Data
@Builder
public class MemoryMessageVO {

    /**
     * 消息角色: user, assistant, system, tool
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息元数据
     */
    private Map<String, Object> metadata;

    /**
     * 是否有工具调用（仅 assistant 角色）
     */
    private boolean hasToolCalls;

    /**
     * 是否有工具响应（仅 tool 角色）
     */
    private boolean hasToolResponses;
}
