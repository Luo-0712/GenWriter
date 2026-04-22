package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.rag.chunking.ChunkingConfig;
import com.example.genwriter.rag.chunking.DocumentChunkStrategy;
import com.example.genwriter.rag.chunking.DocumentChunkStrategyFactory;
import com.example.genwriter.rag.chunking.DocumentChunkingConfig;
import com.example.genwriter.rag.documentloader.DocumentLoader;
import com.example.genwriter.service.DocumentChunkingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentChunkingServiceImpl implements DocumentChunkingService {

    private final DocumentChunkStrategyFactory strategyFactory;
    private final DocumentLoader documentLoader;
    private final DocumentChunkingConfig chunkingConfig;

    @Override
    public List<DocumentChunk> chunkText(String text, String strategy, ChunkingConfig config) {
        log.info("Chunking text with strategy: {}, config: chunkSize={}, overlap={}", 
                strategy, config.getChunkSize(), config.getChunkOverlap());

        DocumentChunkStrategy chunkStrategy = strategyFactory.getStrategy(strategy);
        List<DocumentChunk> chunks = chunkStrategy.chunk(text, config);

        log.info("Text chunked into {} chunks", chunks.size());
        return chunks;
    }

    @Override
    public List<DocumentChunk> chunkText(String text) {
        ChunkingConfig defaultConfig = ChunkingConfig.builder()
                .chunkSize(chunkingConfig.getDefaultChunkSize())
                .chunkOverlap(chunkingConfig.getDefaultChunkOverlap())
                .build();
        return chunkText(text, chunkingConfig.getDefaultStrategy(), defaultConfig);
    }

    @Override
    public List<DocumentChunk> chunkDocument(String filePath, String strategy, ChunkingConfig config) {
        log.info("Loading and chunking document: {} with strategy: {}", filePath, strategy);

        try {
            String content = documentLoader.loadDocument(filePath);
            return chunkText(content, strategy, config);
        } catch (Exception e) {
            log.error("Failed to chunk document: {}", filePath, e);
            throw new BizException("DOCUMENT_CHUNKING_ERROR", "Failed to chunk document: " + e.getMessage());
        }
    }

    @Override
    public List<DocumentChunk> chunkDocument(String filePath) {
        ChunkingConfig defaultConfig = ChunkingConfig.builder()
                .chunkSize(chunkingConfig.getDefaultChunkSize())
                .chunkOverlap(chunkingConfig.getDefaultChunkOverlap())
                .build();
        return chunkDocument(filePath, chunkingConfig.getDefaultStrategy(), defaultConfig);
    }

    @Override
    public List<String> getAvailableStrategies() {
        return List.of("fixed_size", "recursive", "semantic");
    }
}
