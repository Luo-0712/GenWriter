package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.service.RAGPipelineService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RAGPipelineController {

    private final RAGPipelineService ragPipelineService;

    @PostMapping("/process/document")
    public ApiResponse<List<KnowledgeChunkDTO>> processDocument(@Validated @RequestBody ProcessDocumentRequest request) {
        log.info("Processing document into RAG pipeline: {} -> kb:{}", request.getFilePath(), request.getKbId());

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<KnowledgeChunkDTO> chunks = ragPipelineService.processDocument(
                request.getFilePath(), request.getKbId(), strategy);

        return ApiResponse.success(chunks);
    }

    @PostMapping("/process/text")
    public ApiResponse<List<KnowledgeChunkDTO>> processText(@Validated @RequestBody ProcessTextRequest request) {
        log.info("Processing text into RAG pipeline: kb:{}", request.getKbId());

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<KnowledgeChunkDTO> chunks = ragPipelineService.processText(
                request.getText(), request.getKbId(), request.getSourceId(), strategy);

        return ApiResponse.success(chunks);
    }

    @PostMapping("/search")
    public ApiResponse<List<KnowledgeChunkDTO>> search(@Validated @RequestBody SearchRequest request) {
        log.info("RAG search: kb:{}, query:{}", request.getKbId(), request.getQuery());

        int topK = request.getTopK() != null ? request.getTopK() : 5;
        List<KnowledgeChunkDTO> results = ragPipelineService.searchAndRetrieve(
                request.getQuery(), request.getKbId(), topK);

        return ApiResponse.success(results);
    }

    @PostMapping("/generate")
    public ApiResponse<String> generateWithContext(@Validated @RequestBody GenerateRequest request) {
        log.info("RAG generate with context: kb:{}, query:{}", request.getKbId(), request.getQuery());

        String response = ragPipelineService.generateResponseWithContext(
                request.getQuery(), request.getKbId());

        return ApiResponse.success(response);
    }

    @Data
    @Builder
    static class ProcessDocumentRequest {
        @NotBlank(message = "File path cannot be empty")
        private String filePath;
        @NotBlank(message = "Knowledge base ID cannot be empty")
        private String kbId;
        private String strategy;
    }

    @Data
    @Builder
    static class ProcessTextRequest {
        @NotBlank(message = "Text cannot be empty")
        private String text;
        @NotBlank(message = "Knowledge base ID cannot be empty")
        private String kbId;
        private String sourceId;
        private String strategy;
    }

    @Data
    @Builder
    static class SearchRequest {
        @NotBlank(message = "Knowledge base ID cannot be empty")
        private String kbId;
        @NotBlank(message = "Query cannot be empty")
        private String query;
        @Min(value = 1, message = "TopK must be at least 1")
        private Integer topK;
    }

    @Data
    @Builder
    static class GenerateRequest {
        @NotBlank(message = "Knowledge base ID cannot be empty")
        private String kbId;
        @NotBlank(message = "Query cannot be empty")
        private String query;
    }
}
