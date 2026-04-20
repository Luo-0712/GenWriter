package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 创建写作模板请求
 */
@Data
@Builder
public class CreateWritingTemplateRequest {

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 255, message = "名称长度不能超过255个字符")
    private String name;

    private String description;

    @NotBlank(message = "模板类型不能为空")
    private String type;

    private String category;

    @NotBlank(message = "模板内容不能为空")
    private String content;

    private String variables;

    private String example;

    private Boolean isSystem;

    private String metadata;
}
