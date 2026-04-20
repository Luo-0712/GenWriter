package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 写作模板视图对象
 */
@Data
@Builder
public class WritingTemplateVO {

    private String id;
    private String name;
    private String description;
    private String type;
    private String category;
    private String content;
    private Object variables;
    private String example;
    private Boolean isSystem;
    private Integer usageCount;
    private Object metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
