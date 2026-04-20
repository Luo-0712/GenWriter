package com.example.genwriter.model.dto;

import com.example.genwriter.model.entity.DocumentMetadata;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentContent {
    private String content;
    private DocumentMetadata metadata;
}