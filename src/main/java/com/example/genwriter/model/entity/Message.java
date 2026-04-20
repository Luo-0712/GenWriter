package com.example.genwriter.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体
 * 存储任务会话中的对话消息,支持多种角色和消息类型
 *
 * @TableName message
 */
@Data
@Builder
public class Message {

    /**
     * 消息唯一标识符(UUID)
     */
    private String id;

    /**
     * 所属会话ID,外键关联task_session表
     */
    private String sessionId;

    /**
     * 消息角色: user(用户), assistant(AI助手), system(系统), tool(工具)
     */
    private String role;

    /**
     * 消息类型: text(文本), image(图片), file(文件), thinking(思考过程)
     */
    private String type;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息元数据(JSON格式),存储额外信息如token数、模型名称、工具调用等
     */
    private String metadata;

    /**
     * 父消息ID,用于构建消息链(如回复特定消息)
     */
    private String parentId;

    /**
     * 消息序号,用于排序
     */
    private Integer sequence;

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
        Message other = (Message) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getSessionId() == null ? other.getSessionId() == null : this.getSessionId().equals(other.getSessionId()))
                && (this.getRole() == null ? other.getRole() == null : this.getRole().equals(other.getRole()))
                && (this.getType() == null ? other.getType() == null : this.getType().equals(other.getType()))
                && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
                && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
                && (this.getParentId() == null ? other.getParentId() == null : this.getParentId().equals(other.getParentId()))
                && (this.getSequence() == null ? other.getSequence() == null : this.getSequence().equals(other.getSequence()))
                && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
                && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getSessionId() == null) ? 0 : getSessionId().hashCode());
        result = prime * result + ((getRole() == null) ? 0 : getRole().hashCode());
        result = prime * result + ((getType() == null) ? 0 : getType().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getParentId() == null) ? 0 : getParentId().hashCode());
        result = prime * result + ((getSequence() == null) ? 0 : getSequence().hashCode());
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
                ", sessionId=" + sessionId +
                ", role=" + role +
                ", type=" + type +
                ", content=" + (content != null && content.length() > 100 ? content.substring(0, 100) + "..." : content) +
                ", metadata=" + metadata +
                ", parentId=" + parentId +
                ", sequence=" + sequence +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
