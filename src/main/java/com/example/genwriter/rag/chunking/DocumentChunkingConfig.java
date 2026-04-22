package com.example.genwriter.rag.chunking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "app.knowledge.chunking")
public class DocumentChunkingConfig {

    private String defaultStrategy = "recursive";
    private int defaultChunkSize = 1000;
    private int defaultChunkOverlap = 100;
    private boolean keepParagraph = true;
    private boolean keepSentences = true;

    @Bean
    public ChunkingConfig defaultChunkingConfig() {
        return ChunkingConfig.builder()
                .chunkSize(defaultChunkSize)
                .chunkOverlap(defaultChunkOverlap)
                .keepParagraph(keepParagraph)
                .keepSentences(keepSentences)
                .build();
    }

}
