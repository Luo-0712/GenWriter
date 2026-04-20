package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 更新写作模板请求
 */
@Data
@Builder
public class UpdateWritingTemplateRequest {

    @Size(max = 255, message = "名称长度不能超过255个字符")
    private String name;

    private String description;

    private String type;

    private String category;

    private String content;

    private String variables;

    private String example;

    private Boolean isSystem;

    private String metadata;
}
