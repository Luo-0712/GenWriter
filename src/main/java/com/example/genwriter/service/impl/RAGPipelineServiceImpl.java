package com.example.genwriter.service.impl;

import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.rag.chunking.ChunkingConfig;
import com.example.genwriter.rag.chunking.DocumentChunkingConfig;
import com.example.genwriter.service.DocumentChunkingService;
import com.example.genwriter.service.EmbeddingService;
import com.example.genwriter.service.KnowledgeChunkService;
import com.example.genwriter.service.RAGPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGPipelineServiceImpl implements RAGPipelineService {

    private final DocumentChunkingService chunkingService;
    private final KnowledgeChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final DocumentChunkingConfig chunkingConfig;

    @Value("${app.knowledge.default-search-limit:5}")
    private int defaultSearchLimit;

    @Value("${app.knowledge.similarity-threshold:0.7}")
    private double similarityThreshold;

    @Override
    @Transactional
    public List<KnowledgeChunkDTO> processDocument(String filePath, String kbId, String strategy) {
        log.info("Processing document: {} into knowledge base: {} with strategy: {}", filePath, kbId, strategy);

        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(chunkingConfig.getDefaultChunkSize())
                .chunkOverlap(chunkingConfig.getDefaultChunkOverlap())
                .sourceDocumentId(filePath)
                .build();

        List<DocumentChunk> chunks = chunkingService.chunkDocument(filePath, strategy, config);

        return saveChunks(chunks, kbId, filePath);
    }

    @Override
    @Transactional
    public List<KnowledgeChunkDTO> processDocument(String filePath, String kbId) {
        return processDocument(filePath, kbId, chunkingConfig.getDefaultStrategy());
    }

    @Override
    @Transactional
    public List<KnowledgeChunkDTO> processText(String text, String kbId, String sourceId, String strategy) {
        log.info("Processing text into knowledge base: {} with strategy: {}", kbId, strategy);

        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(chunkingConfig.getDefaultChunkSize())
                .chunkOverlap(chunkingConfig.getDefaultChunkOverlap())
                .sourceDocumentId(sourceId)
                .build();

        List<DocumentChunk> chunks = chunkingService.chunkText(text, strategy, config);

        return saveChunks(chunks, kbId, sourceId);
    }

    @Override
    @Transactional
    public List<KnowledgeChunkDTO> processText(String text, String kbId, String sourceId) {
        return processText(text, kbId, sourceId, chunkingConfig.getDefaultStrategy());
    }

    @Override
    public List<KnowledgeChunkDTO> searchAndRetrieve(String query, String kbId, int topK) {
        log.info("Searching knowledge base: {} with query: {}, topK: {}", kbId, query, topK);

        SearchKnowledgeChunkRequest request = SearchKnowledgeChunkRequest.builder()
                .kbId(kbId)
                .query(query)
                .limit(topK)
                .threshold(similarityThreshold)
                .build();

        return chunkService.searchSimilarChunks(request);
    }

    @Override
    public String generateResponseWithContext(String query, String kbId) {
        List<KnowledgeChunkDTO> context = searchAndRetrieve(query, kbId, defaultSearchLimit);

        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("Based on the following knowledge base context:\n\n");

        for (int i = 0; i < context.size(); i++) {
            KnowledgeChunkDTO chunk = context.get(i);
            contextBuilder.append("[").append(i + 1).append("] ").append(chunk.getContent()).append("\n\n");
        }

        contextBuilder.append("User query: ").append(query).append("\n");
        contextBuilder.append("Please provide a comprehensive answer based on the context above.");

        return contextBuilder.toString();
    }

    private List<KnowledgeChunkDTO> saveChunks(List<DocumentChunk> chunks, String kbId, String sourceId) {
        if (chunks.isEmpty()) {
            log.warn("No chunks to save for document: {}", sourceId);
            return List.of();
        }

        // 批量生成嵌入
        List<String> contents = chunks.stream()
                .map(DocumentChunk::getContent)
                .toList();
        List<float[]> embeddings = embeddingService.embed(contents);

        List<CreateKnowledgeChunkRequest> requests = IntStream.range(0, chunks.size())
                .mapToObj(i -> CreateKnowledgeChunkRequest.builder()
                        .kbId(kbId)
                        .sourceId(sourceId)
                        .content(chunks.get(i).getContent())
                        .embedding(embeddings.get(i))
                        .metadata(chunks.get(i).getMetadata())
                        .build())
                .toList();

        List<KnowledgeChunkDTO> savedChunks = chunkService.createChunks(requests);

        log.info("Successfully saved {} chunks to knowledge base: {}", savedChunks.size(), kbId);
        return savedChunks;
    }
}
