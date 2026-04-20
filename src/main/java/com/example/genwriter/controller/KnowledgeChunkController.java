package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;
import com.example.genwriter.model.vo.KnowledgeChunkVO;
import com.example.genwriter.service.KnowledgeChunkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识片段控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-chunks")
@RequiredArgsConstructor
public class KnowledgeChunkController {

    private final KnowledgeChunkService knowledgeChunkService;

    @PostMapping
    public ApiResponse<KnowledgeChunkVO> createChunk(@Valid @RequestBody CreateKnowledgeChunkRequest request) {
        log.info("创建知识片段请求: kbId={}", request.getKbId());
        KnowledgeChunkDTO dto = knowledgeChunkService.createChunk(request);
        return ApiResponse.success(convertToVO(dto));
    }

    @PostMapping("/batch")
    public ApiResponse<List<KnowledgeChunkVO>> createChunks(@Valid @RequestBody List<CreateKnowledgeChunkRequest> requests) {
        log.info("批量创建知识片段请求: {} 个", requests.size());
        List<KnowledgeChunkDTO> dtos = knowledgeChunkService.createChunks(requests);
        List<KnowledgeChunkVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeChunkVO> getChunk(@PathVariable String id) {
        log.info("查询知识片段: {}", id);
        KnowledgeChunkDTO dto = knowledgeChunkService.getChunkById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping("/kb/{kbId}")
    public ApiResponse<List<KnowledgeChunkVO>> getChunksByKbId(@PathVariable String kbId) {
        log.info("查询知识库片段: {}", kbId);
        List<KnowledgeChunkDTO> dtos = knowledgeChunkService.getChunksByKbId(kbId);
        List<KnowledgeChunkVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @PostMapping("/search")
    public ApiResponse<List<KnowledgeChunkVO>> searchSimilarChunks(@Valid @RequestBody SearchKnowledgeChunkRequest request) {
        log.info("相似度搜索请求: kbId={}", request.getKbId());
        List<KnowledgeChunkDTO> dtos = knowledgeChunkService.searchSimilarChunks(request);
        List<KnowledgeChunkVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteChunk(@PathVariable String id) {
        log.info("删除知识片段: {}", id);
        knowledgeChunkService.deleteChunk(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/kb/{kbId}")
    public ApiResponse<Void> deleteChunksByKbId(@PathVariable String kbId) {
        log.info("删除知识库的所有片段: {}", kbId);
        knowledgeChunkService.deleteChunksByKbId(kbId);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/source/{sourceId}")
    public ApiResponse<Void> deleteChunksBySourceId(@PathVariable String sourceId) {
        log.info("删除源文档的所有片段: {}", sourceId);
        knowledgeChunkService.deleteChunksBySourceId(sourceId);
        return ApiResponse.success(null);
    }

    private KnowledgeChunkVO convertToVO(KnowledgeChunkDTO dto) {
        return KnowledgeChunkVO.builder()
                .id(dto.getId())
                .kbId(dto.getKbId())
                .sourceId(dto.getSourceId())
                .content(dto.getContent())
                .embeddingDimension(dto.getEmbeddingDimension())
                .embeddingModel(dto.getEmbeddingModel())
                .metadata(parseMetadata(dto.getMetadata()))
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .distance(dto.getDistance())
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
