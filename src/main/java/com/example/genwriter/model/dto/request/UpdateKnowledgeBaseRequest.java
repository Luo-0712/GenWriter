package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 更新知识库请求
 */
@Data
@Builder
public class UpdateKnowledgeBaseRequest {

    @Size(max = 255, message = "名称长度不能超过255个字符")
    private String name;

    private String description;

    private String type;

    private String metadata;
}
