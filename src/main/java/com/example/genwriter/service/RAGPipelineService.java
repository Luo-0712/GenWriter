package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;

import java.util.List;

public interface RAGPipelineService {
    List<KnowledgeChunkDTO> processDocument(String filePath, String kbId, String strategy);
    List<KnowledgeChunkDTO> processDocument(String filePath, String kbId);
    List<KnowledgeChunkDTO> processText(String text, String kbId, String sourceId, String strategy);
    List<KnowledgeChunkDTO> processText(String text, String kbId, String sourceId);
    List<KnowledgeChunkDTO> searchAndRetrieve(String query, String kbId, int topK);
    List<KnowledgeChunkDTO> searchAndRetrieve(String query, String kbId, int topK, Double threshold);
    String generateResponseWithContext(String query, String kbId);
}
