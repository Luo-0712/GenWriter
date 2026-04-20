package com.example.genwriter.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息视图对象
 */
@Data
@Builder
public class MessageVO {

    private String id;
    private String sessionId;
    private String role;
    private String type;
    private String content;
    private Object metadata;
    private String parentId;
    private Integer sequence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
