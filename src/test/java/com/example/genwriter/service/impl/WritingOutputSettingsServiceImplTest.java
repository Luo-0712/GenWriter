package com.example.genwriter.service.impl;

import com.example.genwriter.config.WritingProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingOutputSettingsServiceImplTest {

    @Test
    void getSettings_ShouldDisableParallelChapterWritingByDefault() {
        WritingOutputSettingsServiceImpl service = new WritingOutputSettingsServiceImpl(new WritingProperties());
        service.init();

        assertTrue(service.getSettings().markdownEnabled());
        assertFalse(service.getSettings().parallelChapterWritingEnabled());
        assertFalse(service.isParallelChapterWritingEnabled());
    }

    @Test
    void updateSettings_ShouldPartiallyUpdateParallelChapterWriting() {
        WritingOutputSettingsServiceImpl service = new WritingOutputSettingsServiceImpl(new WritingProperties());
        service.init();

        service.updateSettings(null, true);

        assertTrue(service.isMarkdownEnabled());
        assertTrue(service.isParallelChapterWritingEnabled());
    }
}
