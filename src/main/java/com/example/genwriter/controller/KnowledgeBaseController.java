package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateKnowledgeBaseRequest;
import com.example.genwriter.model.dto.response.KnowledgeBaseDTO;
import com.example.genwriter.model.dto.request.UpdateKnowledgeBaseRequest;
import com.example.genwriter.model.vo.KnowledgeBaseVO;
import com.example.genwriter.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public ApiResponse<KnowledgeBaseVO> createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        log.debug("创建知识库请求: {}", request.getName());
        KnowledgeBaseDTO dto = knowledgeBaseService.createKnowledgeBase(request);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeBaseVO> getKnowledgeBase(@PathVariable String id) {
        log.debug("查询知识库: {}", id);
        KnowledgeBaseDTO dto = knowledgeBaseService.getKnowledgeBaseById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeBaseVO>> getAllKnowledgeBases() {
        log.debug("查询所有知识库");
        List<KnowledgeBaseDTO> dtos = knowledgeBaseService.getAllKnowledgeBases();
        List<KnowledgeBaseVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<KnowledgeBaseVO>> getKnowledgeBasesByType(@PathVariable String type) {
        log.debug("根据类型查询知识库: {}", type);
        List<KnowledgeBaseDTO> dtos = knowledgeBaseService.getKnowledgeBasesByType(type);
        List<KnowledgeBaseVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/search")
    public ApiResponse<List<KnowledgeBaseVO>> searchKnowledgeBases(@RequestParam String name) {
        log.debug("搜索知识库: {}", name);
        List<KnowledgeBaseDTO> dtos = knowledgeBaseService.searchKnowledgeBasesByName(name);
        List<KnowledgeBaseVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @PutMapping("/{id}")
    public ApiResponse<KnowledgeBaseVO> updateKnowledgeBase(
            @PathVariable String id,
            @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        log.debug("更新知识库: {}", id);
        KnowledgeBaseDTO dto = knowledgeBaseService.updateKnowledgeBase(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable String id) {
        log.debug("删除知识库: {}", id);
        knowledgeBaseService.deleteKnowledgeBase(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteKnowledgeBases(@RequestBody List<String> ids) {
        log.debug("批量删除知识库: {}", ids);
        knowledgeBaseService.deleteKnowledgeBases(ids);
        return ApiResponse.success(null);
    }

    private KnowledgeBaseVO convertToVO(KnowledgeBaseDTO dto) {
        return KnowledgeBaseVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .type(dto.getType())
                .metadata(parseMetadata(dto.getMetadata()))
                .chunkCount(dto.getChunkCount())
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
