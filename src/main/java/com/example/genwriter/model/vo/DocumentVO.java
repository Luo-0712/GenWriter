package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档视图对象
 */
@Data
@Builder
public class DocumentVO {

    private String id;
    private String sessionId;
    private String title;
    private String type;
    private String content;
    private String format;
    private Integer version;
    private String status;
    private Object metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
