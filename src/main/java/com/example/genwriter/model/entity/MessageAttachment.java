package com.example.genwriter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息附件实体
 * 存储用户上传的图片和文档附件
 *
 * @TableName message_attachment
 */
@Data
@Builder
@TableName("message_attachment")
public class MessageAttachment {

    /**
     * 附件唯一标识符(UUID)
     */
    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 关联消息ID,外键关联message表
     */
    private String messageId;

    /**
     * 所属会话ID,外键关联task_session表
     */
    private String sessionId;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 存储文件名(UUID生成)
     */
    private String storedFilename;

    /**
     * 文件存储路径
     */
    private String filePath;

    /**
     * 文件大小(字节)
     */
    private Long fileSize;

    /**
     * 文件MIME类型
     */
    private String mimeType;

    /**
     * 附件类型: IMAGE(图片), DOCUMENT(文档), FILE(其他文件)
     */
    private String attachmentType;

    /**
     * 图片宽度(仅图片类型)
     */
    private Integer width;

    /**
     * 图片高度(仅图片类型)
     */
    private Integer height;

    /**
     * 缩略图路径(仅图片类型)
     */
    private String thumbnailPath;

    /**
     * 文档提取的文本内容(仅文档类型)
     */
    private String extractedText;

    /**
     * 处理状态: PENDING(待处理), PROCESSING(处理中), COMPLETED(已完成), FAILED(失败)
     */
    private String processingStatus;

    /**
     * 元数据(JSON格式)
     */
    private String metadata;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    private LocalDateTime updatedAt;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        MessageAttachment other = (MessageAttachment) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getMessageId() == null ? other.getMessageId() == null : this.getMessageId().equals(other.getMessageId()))
                && (this.getSessionId() == null ? other.getSessionId() == null : this.getSessionId().equals(other.getSessionId()))
                && (this.getOriginalFilename() == null ? other.getOriginalFilename() == null : this.getOriginalFilename().equals(other.getOriginalFilename()))
                && (this.getStoredFilename() == null ? other.getStoredFilename() == null : this.getStoredFilename().equals(other.getStoredFilename()))
                && (this.getFilePath() == null ? other.getFilePath() == null : this.getFilePath().equals(other.getFilePath()))
                && (this.getFileSize() == null ? other.getFileSize() == null : this.getFileSize().equals(other.getFileSize()))
                && (this.getMimeType() == null ? other.getMimeType() == null : this.getMimeType().equals(other.getMimeType()))
                && (this.getAttachmentType() == null ? other.getAttachmentType() == null : this.getAttachmentType().equals(other.getAttachmentType()))
                && (this.getWidth() == null ? other.getWidth() == null : this.getWidth().equals(other.getWidth()))
                && (this.getHeight() == null ? other.getHeight() == null : this.getHeight().equals(other.getHeight()))
                && (this.getThumbnailPath() == null ? other.getThumbnailPath() == null : this.getThumbnailPath().equals(other.getThumbnailPath()))
                && (this.getExtractedText() == null ? other.getExtractedText() == null : this.getExtractedText().equals(other.getExtractedText()))
                && (this.getProcessingStatus() == null ? other.getProcessingStatus() == null : this.getProcessingStatus().equals(other.getProcessingStatus()))
                && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
                && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
                && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getMessageId() == null) ? 0 : getMessageId().hashCode());
        result = prime * result + ((getSessionId() == null) ? 0 : getSessionId().hashCode());
        result = prime * result + ((getOriginalFilename() == null) ? 0 : getOriginalFilename().hashCode());
        result = prime * result + ((getStoredFilename() == null) ? 0 : getStoredFilename().hashCode());
        result = prime * result + ((getFilePath() == null) ? 0 : getFilePath().hashCode());
        result = prime * result + ((getFileSize() == null) ? 0 : getFileSize().hashCode());
        result = prime * result + ((getMimeType() == null) ? 0 : getMimeType().hashCode());
        result = prime * result + ((getAttachmentType() == null) ? 0 : getAttachmentType().hashCode());
        result = prime * result + ((getWidth() == null) ? 0 : getWidth().hashCode());
        result = prime * result + ((getHeight() == null) ? 0 : getHeight().hashCode());
        result = prime * result + ((getThumbnailPath() == null) ? 0 : getThumbnailPath().hashCode());
        result = prime * result + ((getExtractedText() == null) ? 0 : getExtractedText().hashCode());
        result = prime * result + ((getProcessingStatus() == null) ? 0 : getProcessingStatus().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", id=" + id +
                ", messageId=" + messageId +
                ", sessionId=" + sessionId +
                ", originalFilename=" + originalFilename +
                ", storedFilename=" + storedFilename +
                ", filePath=" + filePath +
                ", fileSize=" + fileSize +
                ", mimeType=" + mimeType +
                ", attachmentType=" + attachmentType +
                ", width=" + width +
                ", height=" + height +
                ", thumbnailPath=" + thumbnailPath +
                ", extractedText=" + (extractedText != null && extractedText.length() > 100 ? extractedText.substring(0, 100) + "..." : extractedText) +
                ", processingStatus=" + processingStatus +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
