package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档数据传输对象
 */
@Data
@Builder
public class DocumentDTO {

    private String id;
    private String sessionId;
    private String title;
    private String type;
    private String content;
    private String format;
    private Integer version;
    private String status;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
