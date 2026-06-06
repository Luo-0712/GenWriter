package com.example.genwriter.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("long_term_memory")
public class LongTermMemory {

    @TableId(value = "id", type = IdType.ASSIGN_UUID)
    private String id;
    private String content;
    private String memoryType;
    private String scope;
    private String projectId;
    private String sessionId;
    private float[] embedding;
    private String embeddingModel;
    private String importance;
    private String metadata;
    private Integer accessCount;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(exist = false)
    private Double similarity;

    @Override
    public boolean equals(Object that) {
        if (this == that) return true;
        if (that == null || getClass() != that.getClass()) return false;
        LongTermMemory other = (LongTermMemory) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
                && (this.getMemoryType() == null ? other.getMemoryType() == null : this.getMemoryType().equals(other.getMemoryType()))
                && (this.getScope() == null ? other.getScope() == null : this.getScope().equals(other.getScope()))
                && (this.getProjectId() == null ? other.getProjectId() == null : this.getProjectId().equals(other.getProjectId()))
                && (this.getSessionId() == null ? other.getSessionId() == null : this.getSessionId().equals(other.getSessionId()))
                && (this.getEmbedding() == null ? other.getEmbedding() == null : Arrays.equals(this.getEmbedding(), other.getEmbedding()))
                && (this.getEmbeddingModel() == null ? other.getEmbeddingModel() == null : this.getEmbeddingModel().equals(other.getEmbeddingModel()))
                && (this.getImportance() == null ? other.getImportance() == null : this.getImportance().equals(other.getImportance()))
                && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
                && (this.getAccessCount() == null ? other.getAccessCount() == null : this.getAccessCount().equals(other.getAccessCount()))
                && (this.getLastAccessedAt() == null ? other.getLastAccessedAt() == null : this.getLastAccessedAt().equals(other.getLastAccessedAt()))
                && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
                && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()))
                && (this.getSimilarity() == null ? other.getSimilarity() == null : this.getSimilarity().equals(other.getSimilarity()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getMemoryType() == null) ? 0 : getMemoryType().hashCode());
        result = prime * result + ((getScope() == null) ? 0 : getScope().hashCode());
        result = prime * result + ((getProjectId() == null) ? 0 : getProjectId().hashCode());
        result = prime * result + ((getSessionId() == null) ? 0 : getSessionId().hashCode());
        result = prime * result + ((getEmbedding() == null) ? 0 : Arrays.hashCode(getEmbedding()));
        result = prime * result + ((getEmbeddingModel() == null) ? 0 : getEmbeddingModel().hashCode());
        result = prime * result + ((getImportance() == null) ? 0 : getImportance().hashCode());
        result = prime * result + ((getMetadata() == null) ? 0 : getMetadata().hashCode());
        result = prime * result + ((getAccessCount() == null) ? 0 : getAccessCount().hashCode());
        result = prime * result + ((getLastAccessedAt() == null) ? 0 : getLastAccessedAt().hashCode());
        result = prime * result + ((getCreatedAt() == null) ? 0 : getCreatedAt().hashCode());
        result = prime * result + ((getUpdatedAt() == null) ? 0 : getUpdatedAt().hashCode());
        result = prime * result + ((getSimilarity() == null) ? 0 : getSimilarity().hashCode());
        return result;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [Hash = " + hashCode() +
                ", id=" + id +
                ", content=" + (content != null && content.length() > 100 ? content.substring(0, 100) + "..." : content) +
                ", memoryType=" + memoryType +
                ", scope=" + scope +
                ", projectId=" + projectId +
                ", sessionId=" + sessionId +
                ", embedding=" + (embedding != null ? "[" + embedding.length + " floats]" : "null") +
                ", embeddingModel=" + embeddingModel +
                ", importance=" + importance +
                ", metadata=" + metadata +
                ", accessCount=" + accessCount +
                ", lastAccessedAt=" + lastAccessedAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", similarity=" + similarity +
                "]";
    }
}
