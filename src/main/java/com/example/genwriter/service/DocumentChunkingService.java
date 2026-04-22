package com.example.genwriter.service;

import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.rag.chunking.ChunkingConfig;

import java.util.List;

public interface DocumentChunkingService {
    List<DocumentChunk> chunkText(String text, String strategy, ChunkingConfig config);
    List<DocumentChunk> chunkText(String text);
    List<DocumentChunk> chunkDocument(String filePath, String strategy, ChunkingConfig config);
    List<DocumentChunk> chunkDocument(String filePath);
    List<String> getAvailableStrategies();
}
