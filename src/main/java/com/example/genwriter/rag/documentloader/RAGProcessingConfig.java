package com.example.genwriter.rag.documentloader;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RAGProcessingConfig {

    @Bean
    public DocumentLoaderFactory documentLoaderFactory(DocumentLoader tikaDocumentLoader) {
        return new DocumentLoaderFactory(tikaDocumentLoader);
    }
}