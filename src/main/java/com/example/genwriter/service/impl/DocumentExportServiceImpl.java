package com.example.genwriter.service.impl;

import com.example.genwriter.service.DocumentExportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class DocumentExportServiceImpl implements DocumentExportService {

    @Override
    public byte[] exportAsMarkdown(String title, String content) {
        String md = "# " + title + "\n\n" + content;
        return md.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] exportAsDocx(String title, String content) {
        try (XWPFDocument doc = new XWPFDocument()) {
            // Title as heading
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setStyle("Heading1");
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            // Content: split by lines, handle markdown-like structure
            String[] lines = content.split("\n");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    doc.createParagraph();
                } else if (trimmed.startsWith("### ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading3");
                    XWPFRun run = p.createRun();
                    run.setText(trimmed.substring(4));
                    run.setBold(true);
                    run.setFontSize(13);
                } else if (trimmed.startsWith("## ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading2");
                    XWPFRun run = p.createRun();
                    run.setText(trimmed.substring(3));
                    run.setBold(true);
                    run.setFontSize(15);
                } else if (trimmed.startsWith("# ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setStyle("Heading1");
                    XWPFRun run = p.createRun();
                    run.setText(trimmed.substring(2));
                    run.setBold(true);
                    run.setFontSize(18);
                } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    XWPFParagraph p = doc.createParagraph();
                    p.setIndentationLeft(720);
                    XWPFRun run = p.createRun();
                    run.setText("• " + trimmed.substring(2));
                    run.setFontSize(11);
                } else {
                    XWPFParagraph p = doc.createParagraph();
                    XWPFRun run = p.createRun();
                    run.setText(trimmed);
                    run.setFontSize(11);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.error("生成Word文档失败", e);
            throw new RuntimeException("导出Word文档失败", e);
        }
    }
}
