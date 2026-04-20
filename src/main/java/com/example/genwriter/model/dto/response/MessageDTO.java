package com.example.genwriter.model.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息数据传输对象
 */
@Data
@Builder
public class MessageDTO {

    private String id;
    private String sessionId;
    private String role;
    private String type;
    private String content;
    private String metadata;
    private String parentId;
    private Integer sequence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
