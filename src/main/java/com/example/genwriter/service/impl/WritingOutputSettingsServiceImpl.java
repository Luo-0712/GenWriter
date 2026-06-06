package com.example.genwriter.service.impl;

import com.example.genwriter.config.WritingProperties;
import com.example.genwriter.model.dto.response.WritingOutputSettings;
import com.example.genwriter.service.WritingOutputSettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WritingOutputSettingsServiceImpl implements WritingOutputSettingsService {

    private final WritingProperties writingProperties;

    private volatile boolean markdownEnabled = true;

    @PostConstruct
    void init() {
        markdownEnabled = !"plain".equalsIgnoreCase(writingProperties.getDefaultFormat())
                && !"text".equalsIgnoreCase(writingProperties.getDefaultFormat());
    }

    @Override
    public WritingOutputSettings getSettings() {
        return new WritingOutputSettings(markdownEnabled, currentFormat());
    }

    @Override
    public WritingOutputSettings updateMarkdownEnabled(boolean markdownEnabled) {
        this.markdownEnabled = markdownEnabled;
        return getSettings();
    }

    @Override
    public boolean isMarkdownEnabled() {
        return markdownEnabled;
    }

    @Override
    public String currentFormat() {
        return markdownEnabled ? "markdown" : "plain";
    }
}
