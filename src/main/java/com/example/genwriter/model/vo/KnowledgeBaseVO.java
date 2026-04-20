package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库视图对象
 */
@Data
@Builder
public class KnowledgeBaseVO {

    private String id;
    private String name;
    private String description;
    private String type;
    private Object metadata;
    private Long chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
