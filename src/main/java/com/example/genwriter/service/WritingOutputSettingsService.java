package com.example.genwriter.service;

import com.example.genwriter.model.dto.response.WritingOutputSettings;

public interface WritingOutputSettingsService {

    WritingOutputSettings getSettings();

    WritingOutputSettings updateMarkdownEnabled(boolean markdownEnabled);

    boolean isMarkdownEnabled();

    String currentFormat();
}
