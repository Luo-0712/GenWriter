package com.example.genwriter.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalContent {
    private String text;
    private List<AttachmentRef> attachments;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AttachmentRef {
        private String attachmentId;
        private String type;       // IMAGE / DOCUMENT / FILE
        private String mimeType;
        private String fileUrl;    // 文件访问 URL，用于构建 Media 对象
        private String fileName;   // 原始文件名，用于 prompt 描述
    }

    /** 纯文本快捷构造 */
    public static MultimodalContent ofText(String text) {
        return new MultimodalContent(text, List.of());
    }

    /** 是否包含图片 */
    public boolean hasImages() {
        return attachments != null && attachments.stream()
                .anyMatch(a -> "IMAGE".equals(a.getType()));
    }

    /** 是否包含文档 */
    public boolean hasDocuments() {
        return attachments != null && attachments.stream()
                .anyMatch(a -> "DOCUMENT".equals(a.getType()));
    }

    /** 提取纯文本（兼容旧逻辑） */
    public String getTextOnly() {
        return text != null ? text : "";
    }

    /** 获取图片类型的附件列表 */
    public List<AttachmentRef> getImageAttachments() {
        if (attachments == null) return List.of();
        return attachments.stream()
                .filter(a -> "IMAGE".equals(a.getType()))
                .toList();
    }

    /** 获取文档类型的附件列表 */
    public List<AttachmentRef> getDocumentAttachments() {
        if (attachments == null) return List.of();
        return attachments.stream()
                .filter(a -> "DOCUMENT".equals(a.getType()))
                .toList();
    }
}
