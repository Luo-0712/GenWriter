package com.example.genwriter.model.dto.request;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateMemoryRequest {

    private String content;

    private String memoryType;

    private String scope;

    private String projectId;

    private String importance;
}
