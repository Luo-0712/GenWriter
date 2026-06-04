package com.example.genwriter.model.vo;

import com.example.genwriter.model.entity.MessageAttachment;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息附件视图对象
 */
@Data
@Builder
public class MessageAttachmentVO {

    private String id;
    private String sessionId;
    private String originalFilename;
    private Long fileSize;
    private String mimeType;
    private String attachmentType;
    private Integer width;
    private Integer height;
    private String fileUrl;
    private String thumbnailUrl;
    private String processingStatus;
    private LocalDateTime createdAt;

    /**
     * 从实体创建VO
     *
     * @param entity 附件实体
     * @return VO对象
     */
    public static MessageAttachmentVO fromEntity(MessageAttachment entity) {
        if (entity == null) {
            return null;
        }
        return MessageAttachmentVO.builder()
                .id(entity.getId())
                .sessionId(entity.getSessionId())
                .originalFilename(entity.getOriginalFilename())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .attachmentType(entity.getAttachmentType())
                .width(entity.getWidth())
                .height(entity.getHeight())
                .fileUrl("/api/attachments/" + entity.getId() + "/file")
                .thumbnailUrl("/api/attachments/" + entity.getId() + "/thumbnail")
                .processingStatus(entity.getProcessingStatus())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
