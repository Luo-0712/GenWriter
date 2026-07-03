package com.example.genwriter.model.dto.request;

import com.example.genwriter.model.enums.DocumentEditSuggestionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentEditSuggestionRequest {

    @NotNull(message = "编辑模式不能为空")
    private DocumentEditSuggestionMode mode;

    @Size(max = 1000, message = "补充要求长度不能超过1000个字符")
    private String instruction;

    @Size(max = 500, message = "标题长度不能超过500个字符")
    private String title;

    @NotBlank(message = "选中文本不能为空")
    @Size(max = 12000, message = "选中文本长度不能超过12000个字符")
    private String selectedText;

    @NotBlank(message = "选中Markdown不能为空")
    @Size(max = 12000, message = "选中Markdown长度不能超过12000个字符")
    private String selectedMarkdown;

    @Size(max = 4000, message = "前置上下文长度不能超过4000个字符")
    private String beforeText;

    @Size(max = 4000, message = "后置上下文长度不能超过4000个字符")
    private String afterText;

    @Size(max = 128, message = "选区指纹长度不能超过128个字符")
    private String selectionFingerprint;

    private Integer clientDocumentVersion;
}
