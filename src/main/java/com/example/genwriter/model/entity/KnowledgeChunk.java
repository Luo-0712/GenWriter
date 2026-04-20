package com.example.genwriter.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;

/**
 * 知识片段实体
 * 存储知识库中的向量化文本片段,支持语义检索
 *
 * @TableName knowledge_chunk
 */
@Data
@Builder
public class KnowledgeChunk {

    /**
     * 片段唯一标识符(UUID)
     */
    private String id;

    /**
     * 所属知识库ID,外键关联knowledge_base表
     */
    private String kbId;

    /**
     * 原始文档ID(可选)
     */
    private String sourceId;

    /**
     * 片段内容
     */
    private String content;

    /**
     * 嵌入向量,用于语义相似度搜索
     */
    private float[] embedding;

    /**
     * 向量维度
     */
    private Integer embeddingDimension;

    /**
     * 嵌入模型类型标识
     */
    private String embeddingModel;

    /**
     * 元数据(JSON格式),存储来源位置、权重等
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
        KnowledgeChunk other = (KnowledgeChunk) that;
        return (this.getId() == null ? other.getId() == null : this.getId().equals(other.getId()))
                && (this.getKbId() == null ? other.getKbId() == null : this.getKbId().equals(other.getKbId()))
                && (this.getSourceId() == null ? other.getSourceId() == null : this.getSourceId().equals(other.getSourceId()))
                && (this.getContent() == null ? other.getContent() == null : this.getContent().equals(other.getContent()))
                && (this.getEmbedding() == null ? other.getEmbedding() == null : Arrays.equals(this.getEmbedding(), other.getEmbedding()))
                && (this.getEmbeddingDimension() == null ? other.getEmbeddingDimension() == null : this.getEmbeddingDimension().equals(other.getEmbeddingDimension()))
                && (this.getEmbeddingModel() == null ? other.getEmbeddingModel() == null : this.getEmbeddingModel().equals(other.getEmbeddingModel()))
                && (this.getMetadata() == null ? other.getMetadata() == null : this.getMetadata().equals(other.getMetadata()))
                && (this.getCreatedAt() == null ? other.getCreatedAt() == null : this.getCreatedAt().equals(other.getCreatedAt()))
                && (this.getUpdatedAt() == null ? other.getUpdatedAt() == null : this.getUpdatedAt().equals(other.getUpdatedAt()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getId() == null) ? 0 : getId().hashCode());
        result = prime * result + ((getKbId() == null) ? 0 : getKbId().hashCode());
        result = prime * result + ((getSourceId() == null) ? 0 : getSourceId().hashCode());
        result = prime * result + ((getContent() == null) ? 0 : getContent().hashCode());
        result = prime * result + ((getEmbedding() == null) ? 0 : Arrays.hashCode(getEmbedding()));
        result = prime * result + ((getEmbeddingDimension() == null) ? 0 : getEmbeddingDimension().hashCode());
        result = prime * result + ((getEmbeddingModel() == null) ? 0 : getEmbeddingModel().hashCode());
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
                ", kbId=" + kbId +
                ", sourceId=" + sourceId +
                ", content=" + (content != null && content.length() > 100 ? content.substring(0, 100) + "..." : content) +
                ", embedding=" + (embedding != null ? "[" + embedding.length + " floats]" : "null") +
                ", embeddingDimension=" + embeddingDimension +
                ", embeddingModel=" + embeddingModel +
                ", metadata=" + metadata +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
