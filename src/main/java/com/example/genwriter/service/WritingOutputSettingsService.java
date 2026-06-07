package com.example.genwriter.service;

import com.example.genwriter.model.dto.response.WritingOutputSettings;

public interface WritingOutputSettingsService {

    WritingOutputSettings getSettings();

    WritingOutputSettings updateMarkdownEnabled(boolean markdownEnabled);

    WritingOutputSettings updateSettings(Boolean markdownEnabled, Boolean parallelChapterWritingEnabled);

    boolean isMarkdownEnabled();

    boolean isParallelChapterWritingEnabled();

    String currentFormat();
}
