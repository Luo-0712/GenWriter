package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

/**
 * 创建文档请求
 */
@Data
@Builder
public class CreateDocumentRequest {

    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @NotBlank(message = "文档标题不能为空")
    @Size(max = 500, message = "标题长度不能超过500个字符")
    private String title;

    private String type;

    private String content;

    private String format;

    private String status;

    private String metadata;
}
