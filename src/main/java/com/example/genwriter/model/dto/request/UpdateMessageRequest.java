package com.example.genwriter.model.dto.request;

import lombok.Builder;
import lombok.Data;

/**
 * 更新消息请求
 */
@Data
@Builder
public class UpdateMessageRequest {

    private String role;

    private String type;

    private String content;

    private String metadata;

    private Integer sequence;
}
