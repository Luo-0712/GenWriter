package com.example.genwriter.service.impl;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentExportServiceImplTest {
    private final DocumentExportServiceImpl service = new DocumentExportServiceImpl();

    @Test
    void markdownPreservesTheOrderedBodyAndUnicode() {
        String content = "## 第一章\n\n章引言\n\n### 第一节\n\n节正文";
        assertEquals("# 作品\n\n" + content, new String(service.exportAsMarkdown("作品", content), StandardCharsets.UTF_8));
    }

    @Test
    void wordSupportsAllSixHeadingLevelsWithoutLeavingMarkdownMarkers() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int level = 1; level <= 6; level++) content.append("#".repeat(level)).append(" 层级 ").append(level).append("\n正文 ").append(level).append('\n');
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(service.exportAsDocx("作品", content.toString())))) {
            assertEquals("作品", document.getParagraphs().get(0).getText());
            for (int level = 1; level <= 6; level++) {
                final int expected = level;
                assertTrue(document.getParagraphs().stream().anyMatch(paragraph -> paragraph.getText().equals("层级 " + expected) && ("Heading" + expected).equals(paragraph.getStyle())));
            }
            assertFalse(document.getParagraphs().stream().anyMatch(paragraph -> paragraph.getText().startsWith("#")));
        }
    }
}
