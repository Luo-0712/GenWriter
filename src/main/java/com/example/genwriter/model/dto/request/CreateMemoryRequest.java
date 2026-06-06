package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class CreateMemoryRequest {

    @NotBlank(message = "记忆内容不能为空")
    private String content;

    @NotBlank(message = "记忆类型不能为空")
    private String memoryType;

    private String scope;

    private String projectId;

    private String importance;

    private Map<String, Object> metadata;
}
