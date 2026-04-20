package com.example.genwriter.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentMetadata {
    private String title;
    private String author;
    private long fileSize;
    private String contentType;
    private LocalDateTime createdTime;
    private LocalDateTime modifiedTime;
}