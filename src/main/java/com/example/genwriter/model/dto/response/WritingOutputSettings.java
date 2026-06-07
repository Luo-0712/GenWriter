package com.example.genwriter.model.dto.response;

public record WritingOutputSettings(boolean markdownEnabled,
                                    String format,
                                    boolean parallelChapterWritingEnabled) {
}
