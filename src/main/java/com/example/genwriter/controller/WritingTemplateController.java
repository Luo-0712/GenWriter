package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateWritingTemplateRequest;
import com.example.genwriter.model.dto.request.UpdateWritingTemplateRequest;
import com.example.genwriter.model.dto.response.WritingTemplateDTO;
import com.example.genwriter.model.vo.WritingTemplateVO;
import com.example.genwriter.service.WritingTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 写作模板控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class WritingTemplateController {

    private final WritingTemplateService writingTemplateService;

    @PostMapping
    public ApiResponse<WritingTemplateVO> createTemplate(@Valid @RequestBody CreateWritingTemplateRequest request) {
        log.debug("创建模板请求: {}", request.getName());
        WritingTemplateDTO dto = writingTemplateService.createTemplate(request);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping("/{id}")
    public ApiResponse<WritingTemplateVO> getTemplate(@PathVariable String id) {
        log.debug("查询模板: {}", id);
        WritingTemplateDTO dto = writingTemplateService.getTemplateById(id);
        return ApiResponse.success(convertToVO(dto));
    }

    @GetMapping
    public ApiResponse<List<WritingTemplateVO>> getAllTemplates() {
        log.debug("查询所有模板");
        List<WritingTemplateDTO> dtos = writingTemplateService.getAllTemplates();
        List<WritingTemplateVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<WritingTemplateVO>> getTemplatesByType(@PathVariable String type) {
        log.debug("根据类型查询模板: {}", type);
        List<WritingTemplateDTO> dtos = writingTemplateService.getTemplatesByType(type);
        List<WritingTemplateVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/category/{category}")
    public ApiResponse<List<WritingTemplateVO>> getTemplatesByCategory(@PathVariable String category) {
        log.debug("根据分类查询模板: {}", category);
        List<WritingTemplateDTO> dtos = writingTemplateService.getTemplatesByCategory(category);
        List<WritingTemplateVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/system")
    public ApiResponse<List<WritingTemplateVO>> getSystemTemplates() {
        log.debug("查询系统模板");
        List<WritingTemplateDTO> dtos = writingTemplateService.getSystemTemplates();
        List<WritingTemplateVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @GetMapping("/search")
    public ApiResponse<List<WritingTemplateVO>> searchTemplates(@RequestParam String name) {
        log.debug("搜索模板: {}", name);
        List<WritingTemplateDTO> dtos = writingTemplateService.searchTemplatesByName(name);
        List<WritingTemplateVO> vos = dtos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
        return ApiResponse.success(vos);
    }

    @PutMapping("/{id}")
    public ApiResponse<WritingTemplateVO> updateTemplate(
            @PathVariable String id,
            @Valid @RequestBody UpdateWritingTemplateRequest request) {
        log.debug("更新模板: {}", id);
        WritingTemplateDTO dto = writingTemplateService.updateTemplate(id, request);
        return ApiResponse.success(convertToVO(dto));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String id) {
        log.debug("删除模板: {}", id);
        writingTemplateService.deleteTemplate(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/use")
    public ApiResponse<WritingTemplateVO> useTemplate(@PathVariable String id) {
        log.debug("使用模板: {}", id);
        WritingTemplateDTO dto = writingTemplateService.useTemplate(id);
        return ApiResponse.success(convertToVO(dto));
    }

    private WritingTemplateVO convertToVO(WritingTemplateDTO dto) {
        return WritingTemplateVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .type(dto.getType())
                .category(dto.getCategory())
                .content(dto.getContent())
                .variables(parseMetadata(dto.getVariables()))
                .example(dto.getExample())
                .isSystem(dto.getIsSystem())
                .usageCount(dto.getUsageCount())
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
