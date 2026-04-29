package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 创建任务会话请求
 */
@Data
@Builder
public class CreateTaskSessionRequest {

    @NotBlank(message = "会话标题不能为空")
    @Size(max = 255, message = "标题长度不能超过255个字符")
    private String title;

    private String projectId;

    private String type;

    private String topic;

    private String style;

    private String metadata;
}
