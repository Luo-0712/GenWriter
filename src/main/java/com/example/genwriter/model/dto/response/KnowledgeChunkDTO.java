package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识片段数据传输对象
 */
@Data
@Builder
public class KnowledgeChunkDTO {

    private String id;
    private String kbId;
    private String sourceId;
    private String content;
    private float[] embedding;
    private Integer embeddingDimension;
    private String embeddingModel;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Double distance; // 相似度搜索时的距离
}
