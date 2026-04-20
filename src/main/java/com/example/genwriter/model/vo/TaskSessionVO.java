package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务会话视图对象
 */
@Data
@Builder
public class TaskSessionVO {

    private String id;
    private String title;
    private String type;
    private String status;
    private String topic;
    private String style;
    private Object metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long messageCount;
    private Long documentCount;
}
