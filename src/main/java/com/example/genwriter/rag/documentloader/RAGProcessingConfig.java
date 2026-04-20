package com.example.genwriter.rag.documentloader;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RAGProcessingConfig {

    @Bean
    public DocumentLoader documentLoader() {
        return new TikaDocumentLoader();
    }

    @Bean
    public DocumentLoaderFactory documentLoaderFactory(DocumentLoader loader) {
        return new DocumentLoaderFactory(loader);
    }
}