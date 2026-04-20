package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 更新文档请求
 */
@Data
@Builder
public class UpdateDocumentRequest {

    @Size(max = 500, message = "标题长度不能超过500个字符")
    private String title;

    private String type;

    private String content;

    private String format;

    private String status;

    private String metadata;
}
