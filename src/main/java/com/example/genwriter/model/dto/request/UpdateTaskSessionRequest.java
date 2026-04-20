package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 更新任务会话请求
 */
@Data
@Builder
public class UpdateTaskSessionRequest {

    @Size(max = 255, message = "标题长度不能超过255个字符")
    private String title;

    private String type;

    private String status;

    private String topic;

    private String style;

    private String metadata;
}
