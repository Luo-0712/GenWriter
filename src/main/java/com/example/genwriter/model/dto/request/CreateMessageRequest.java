package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

/**
 * 创建消息请求
 */
@Data
@Builder
public class CreateMessageRequest {

    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @NotBlank(message = "消息角色不能为空")
    private String role;

    private String type;

    @NotBlank(message = "消息内容不能为空")
    private String content;

    private String metadata;

    private String parentId;

    private Integer sequence;
}
