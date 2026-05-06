package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryVO {

    private String id;
    private String content;
    private String memoryType;
    private String scope;
    private String projectId;
    private String sessionId;
    private String embeddingModel;
    private String importance;
    private Object metadata;
    private Integer accessCount;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Double similarity;
}
