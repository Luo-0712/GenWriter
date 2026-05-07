package com.example.genwriter.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExportDocumentRequest {

    @NotBlank(message = "会话ID不能为空")
    private String sessionId;

    @NotBlank(message = "文档标题不能为空")
    private String title;

    private String content;

    @NotBlank(message = "导出格式不能为空")
    private String format;
}
