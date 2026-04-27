package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateDocumentRequest;
import com.example.genwriter.model.dto.request.UpdateDocumentRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.vo.DocumentVO;
import com.example.genwriter.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    public ApiResponse<DocumentVO> createDocument(@Valid @RequestBody CreateDocumentRequest request) {
        log.debug("创建文档请求: {}", request.getTitle());
        DocumentDTO dto = documentService.createDocument(request);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping("/{id}")
    public ApiResponse<DocumentVO> getDocument(@PathVariable String id) {
        log.debug("查询文档: {}", id);
        DocumentDTO dto = documentService.getDocumentById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping("/session/{sessionId}")
    public ApiResponse<List<DocumentVO>> getDocumentsBySessionId(@PathVariable String sessionId) {
        log.debug("查询会话文档: {}", sessionId);
        List<DocumentDTO> dtos = documentService.getDocumentsBySessionId(sessionId);
        List<DocumentVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/session/{sessionId}/latest")
    public ApiResponse<DocumentVO> getLatestDocumentBySessionId(@PathVariable String sessionId) {
        log.debug("查询会话最新文档: {}", sessionId);
        DocumentDTO dto = documentService.getLatestDocumentBySessionId(sessionId);
        return ApiResponse.success(convertToVO(dto));
    }

    @PostMapping("/session/{sessionId}/version")
    public ApiResponse<DocumentVO> createNewVersion(
            @PathVariable String sessionId,
            @Valid @RequestBody CreateDocumentRequest request) {
        log.debug("创建文档新版本: {}", sessionId);
        DocumentDTO dto = documentService.createNewVersion(sessionId, request);
        return ApiResponse.success(convertToVO(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<DocumentVO> updateDocument(
            @PathVariable String id,
            @Valid @RequestBody UpdateDocumentRequest request) {
        log.debug("更新文档: {}", id);
        DocumentDTO dto = documentService.updateDocument(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDocument(@PathVariable String id) {
        log.debug("删除文档: {}", id);
        documentService.deleteDocument(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<DocumentVO>> getDocumentsByStatus(@PathVariable String status) {
        log.debug("根据状态查询文档: {}", status);
        List<DocumentDTO> dtos = documentService.getDocumentsByStatus(status);
        List<DocumentVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    private DocumentVO convertToVO(DocumentDTO dto) {
        return DocumentVO.builder()
                .id(dto.getId())
                .sessionId(dto.getSessionId())
                .title(dto.getTitle())
                .type(dto.getType())
                .content(dto.getContent())
                .format(dto.getFormat())
                .version(dto.getVersion())
                .status(dto.getStatus())
                .metadata(parseMetadata(dto.getMetadata()))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    private Object parseMetadata(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(metadata, Object.class);
        } catch (Exception e) {
            return metadata;
        }
    }
}
