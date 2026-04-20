package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务会话数据传输对象
 */
@Data
@Builder
public class TaskSessionDTO {

    private String id;
    private String title;
    private String type;
    private String status;
    private String topic;
    private String style;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long messageCount;
    private Long documentCount;
}
