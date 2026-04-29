package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目数据传输对象
 */
@Data
@Builder
public class ProjectDTO {

    private String id;
    private String name;
    private String description;
    private String status;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long sessionCount;
}
