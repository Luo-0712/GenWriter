package com.example.genwriter.rag.documentloader;

import com.example.genwriter.exception.DocumentLoadException;
import com.example.genwriter.model.entity.DocumentMetadata;

import java.util.List;

public interface DocumentLoader {
    String loadDocument(String filePath) throws DocumentLoadException;
    List<String> supportedFormats();
    DocumentMetadata getMetadata(String filePath) throws DocumentLoadException;
}