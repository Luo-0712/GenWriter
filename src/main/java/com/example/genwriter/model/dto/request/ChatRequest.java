package com.example.genwriter.model.dto.request;

import com.example.genwriter.model.dto.MultimodalContent;
import com.example.genwriter.model.entity.MessageAttachment;
import com.example.genwriter.service.FileStorageService;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    private String text;
    private List<String> attachmentIds;  // 最多 10 个

    /** 校验附件并构建 MultimodalContent */
    public MultimodalContent toMultimodalContent(String sessionId, FileStorageService fileStorageService) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return MultimodalContent.ofText(text);
        }
        if (attachmentIds.size() > 10) {
            throw new com.example.genwriter.exception.BizException(
                    com.example.genwriter.exception.BizException.ErrorCode.ATTACHMENT_UPLOAD_LIMIT);
        }
        // 批量查询附件并校验会话归属
        List<MessageAttachment> attachments = fileStorageService.getByIds(attachmentIds);
        if (attachments.size() != attachmentIds.size()) {
            throw new com.example.genwriter.exception.BizException(
                    com.example.genwriter.exception.BizException.ErrorCode.ATTACHMENT_NOT_FOUND);
        }
        for (MessageAttachment att : attachments) {
            if (!att.getSessionId().equals(sessionId)) {
                throw new com.example.genwriter.exception.BizException(
                        com.example.genwriter.exception.BizException.ErrorCode.ATTACHMENT_SESSION_MISMATCH);
            }
        }
        // 构建 AttachmentRef 列表
        List<MultimodalContent.AttachmentRef> refs = attachments.stream()
                .map(att -> new MultimodalContent.AttachmentRef(
                        att.getId(),
                        att.getAttachmentType(),
                        att.getMimeType(),
                        "/api/attachments/" + att.getId() + "/file",
                        att.getOriginalFilename()))
                .toList();
        return new MultimodalContent(text, refs);
    }
}
