package com.example.genwriter.service;

public interface DocumentExportService {

    byte[] exportAsMarkdown(String title, String content);

    byte[] exportAsDocx(String title, String content);
}
