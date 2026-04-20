package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识片段视图对象
 */
@Data
@Builder
public class KnowledgeChunkVO {

    private String id;
    private String kbId;
    private String sourceId;
    private String content;
    private Integer embeddingDimension;
    private String embeddingModel;
    private Object metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Double distance;
}
