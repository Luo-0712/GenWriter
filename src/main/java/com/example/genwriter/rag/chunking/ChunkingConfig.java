package com.example.genwriter.rag.chunking;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkingConfig {
    @Builder.Default
    private int chunkSize = 1000;
    
    @Builder.Default
    private int chunkOverlap = 100;
    
    @Builder.Default
    private boolean keepParagraph = true;
    
    @Builder.Default
    private boolean keepSentences = true;
    
    private String sourceDocumentId;
    private String metadata;
}
