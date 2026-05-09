package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.service.KnowledgeImportService;
import com.example.genwriter.service.RAGPipelineService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RAGPipelineController {

    private final RAGPipelineService ragPipelineService;
    private final KnowledgeImportService knowledgeImportService;

    @Value("${app.upload.path:uploads}")
    private String uploadPath;

    @PostMapping("/upload")
    public ApiResponse<UploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("kbId") String kbId,
            @RequestParam(value = "strategy", required = false) String strategy) {
        log.debug("Uploading document: {} -> kb:{}", file.getOriginalFilename(), kbId);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        try {
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String taskId = UUID.randomUUID().toString();
            String filename = taskId + "_" + file.getOriginalFilename();
            Path filePath = uploadDir.resolve(filename);
            file.transferTo(filePath);

            knowledgeImportService.processImportAsync(taskId, filePath.toString(), kbId, strategy);

            return ApiResponse.success(UploadResponse.builder()
                    .taskId(taskId)
                    .kbId(kbId)
                    .filename(file.getOriginalFilename())
                    .build());

        } catch (IOException e) {
            log.error("Failed to save uploaded file", e);
            throw new RuntimeException("Failed to save file: " + e.getMessage());
        }
    }

    @PostMapping("/process/document")
    public ApiResponse<List<KnowledgeChunkDTO>> processDocument(@Validated @RequestBody ProcessDocumentRequest request) {
        log.debug("Processing document into RAG pipeline: {} -> kb:{}", request.getFilePath(), request.getKbId());

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<KnowledgeChunkDTO> chunks = ragPipelineService.processDocument(
                request.getFilePath(), request.getKbId(), strategy);

        return ApiResponse.success(chunks);
    }

    @PostMapping("/process/text")
    public ApiResponse<List<KnowledgeChunkDTO>> processText(@Validated @RequestBody ProcessTextRequest request) {
        log.debug("Processing text into RAG pipeline: kb:{}", request.getKbId());

        String strategy = request.getStrategy() != null ? request.getStrategy() : "recursive";
        List<KnowledgeChunkDTO> chunks = ragPipelineService.processText(
                request.getText(), request.getKbId(), request.getSourceId(), strategy);

        return ApiResponse.success(chunks);
    }

    @PostMapping("/search")
    public ApiResponse<List<KnowledgeChunkDTO>> search(@Validated @RequestBody SearchRequest request) {
        log.debug("RAG search: kb:{}, query:{}", request.getKbId(), request.getQuery());

        int topK = request.getTopK() != null ? request.getTopK() : 5;
        List<KnowledgeChunkDTO> results = ragPipelineService.searchAndRetrieve(
                request.getQuery(), request.getKbId(), topK);

        return ApiResponse.success(results);
    }

    @PostMapping("/generate")
    public ApiResponse<String> generateWithContext(@Validated @RequestBody GenerateRequest request) {
        log.debug("RAG generate with context: kb:{}, query:{}", request.getKbId(), request.getQuery());

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

    @Data
    @Builder
    static class UploadResponse {
        private String taskId;
        private String kbId;
        private String filename;
    }
}
