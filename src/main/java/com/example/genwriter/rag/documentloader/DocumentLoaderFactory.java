package com.example.genwriter.rag.documentloader;

import org.springframework.stereotype.Component;

@Component
public class DocumentLoaderFactory {

    private final DocumentLoader loader;

    public DocumentLoaderFactory(DocumentLoader loader) {
        this.loader = loader;
    }

    public DocumentLoader getLoader() {
        return loader;
    }
}