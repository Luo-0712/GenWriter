package com.example.genwriter.rag.chunking;

import com.example.genwriter.model.dto.DocumentChunk;

import java.util.List;

public interface DocumentChunkStrategy {
    List<DocumentChunk> chunk(String text, ChunkingConfig config);
    String getStrategyName();
}
