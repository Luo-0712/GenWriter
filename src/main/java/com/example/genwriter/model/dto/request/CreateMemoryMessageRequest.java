package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 手动添加记忆消息请求
 */
@Data
@Builder
public class CreateMemoryMessageRequest {

    @NotBlank(message = "消息角色不能为空")
    private String role;

    @NotBlank(message = "消息内容不能为空")
    private String content;

    private Map<String, Object> metadata;
}
