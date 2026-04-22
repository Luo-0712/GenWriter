package com.example.genwriter.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentChunk {
    private int index;
    private String content;
    private int startOffset;
    private int endOffset;
    private int tokenCount;
    private String metadata;
}
