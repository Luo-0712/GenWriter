package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 写作模板数据传输对象
 */
@Data
@Builder
public class WritingTemplateDTO {

    private String id;
    private String name;
    private String description;
    private String type;
    private String category;
    private String content;
    private String variables;
    private String example;
    private Boolean isSystem;
    private Integer usageCount;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
