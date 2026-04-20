package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库数据传输对象
 */
@Data
@Builder
public class KnowledgeBaseDTO {

    private String id;
    private String name;
    private String description;
    private String type;
    private String metadata;
    private Long chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
