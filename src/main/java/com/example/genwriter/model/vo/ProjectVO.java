package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目视图对象
 */
@Data
@Builder
public class ProjectVO {

    private String id;
    private String name;
    private String description;
    private String status;
    private Object metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long sessionCount;
}
