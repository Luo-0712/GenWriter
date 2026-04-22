package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.DocumentChunk;
import com.example.genwriter.rag.chunking.ChunkingConfig;
import com.example.genwriter.service.DocumentChunkingService;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chunking")
@RequiredArgsConstructor
public class DocumentChunkingController {

    private final DocumentChunkingService chunkingService;

    @PostMapping("/text")
    public ApiResponse<List<DocumentChunk>> chunkText(@Validated @RequestBody ChunkTextRequest request) {
        log.info("Chunking text with strategy: {}", request.getStrategy());

        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(request.getChunkSize() != null ? request.getChunkSize() : 1000)
                .chunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 100)
                .keepParagraph(request.isKeepParagraph())
                .keepSentences(request.isKeepSentences())
                .build();

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<DocumentChunk> chunks = chunkingService.chunkText(request.getText(), strategy, config);

        return ApiResponse.success(chunks);
    }

    @PostMapping("/document")
    public ApiResponse<List<DocumentChunk>> chunkDocument(@Validated @RequestBody ChunkDocumentRequest request) {
        log.info("Chunking document: {} with strategy: {}", request.getFilePath(), request.getStrategy());

        ChunkingConfig config = ChunkingConfig.builder()
                .chunkSize(request.getChunkSize() != null ? request.getChunkSize() : 1000)
                .chunkOverlap(request.getChunkOverlap() != null ? request.getChunkOverlap() : 100)
                .sourceDocumentId(request.getFilePath())
                .keepParagraph(request.isKeepParagraph())
                .keepSentences(request.isKeepSentences())
                .build();

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<DocumentChunk> chunks = chunkingService.chunkDocument(request.getFilePath(), strategy, config);

        return ApiResponse.success(chunks);
    }

    @GetMapping("/strategies")
    public ApiResponse<List<String>> getStrategies() {
        return ApiResponse.success(chunkingService.getAvailableStrategies());
    }

    @Data
    @Builder
    static class ChunkTextRequest {
        @NotBlank(message = "Text cannot be empty")
        private String text;
        private String strategy;
        private Integer chunkSize;
        private Integer chunkOverlap;
        @Builder.Default
        private boolean keepParagraph = true;
        @Builder.Default
        private boolean keepSentences = true;
    }

    @Data
    @Builder
    static class ChunkDocumentRequest {
        @NotBlank(message = "File path cannot be empty")
        private String filePath;
        private String strategy;
        private Integer chunkSize;
        private Integer chunkOverlap;
        @Builder.Default
        private boolean keepParagraph = true;
        @Builder.Default
        private boolean keepSentences = true;
    }
}
