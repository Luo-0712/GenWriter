package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 创建项目请求
 */
@Data
@Builder
public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 255, message = "名称长度不能超过255个字符")
    private String name;

    private String description;

    private String metadata;
}
