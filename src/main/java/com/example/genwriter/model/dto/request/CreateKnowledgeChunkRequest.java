package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

/**
 * 创建知识片段请求
 */
@Data
@Builder
public class CreateKnowledgeChunkRequest {

    @NotBlank(message = "知识库ID不能为空")
    private String kbId;

    private String sourceId;

    @NotBlank(message = "内容不能为空")
    private String content;

    private float[] embedding;

    private Integer embeddingDimension;

    private String embeddingModel;

    private String metadata;
}
