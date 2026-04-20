package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Data;

/**
 * 搜索知识片段请求
 */
@Data
@Builder
public class SearchKnowledgeChunkRequest {

    @NotBlank(message = "知识库ID不能为空")
    private String kbId;

    @NotBlank(message = "查询文本不能为空")
    private String query;

    @Min(value = 1, message = "限制数量至少为1")
    private Integer limit;

    private Double threshold;
}
