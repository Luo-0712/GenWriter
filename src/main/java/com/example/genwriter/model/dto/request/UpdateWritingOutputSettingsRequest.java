package com.example.genwriter.model.dto.request;

import lombok.Data;

@Data
public class UpdateWritingOutputSettingsRequest {

    private Boolean markdownEnabled;

    private Boolean parallelChapterWritingEnabled;
}
