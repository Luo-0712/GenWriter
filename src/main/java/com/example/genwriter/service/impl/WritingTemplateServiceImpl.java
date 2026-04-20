package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.WritingTemplateMapper;
import com.example.genwriter.model.dto.request.CreateWritingTemplateRequest;
import com.example.genwriter.model.dto.request.UpdateWritingTemplateRequest;
import com.example.genwriter.model.dto.response.WritingTemplateDTO;
import com.example.genwriter.model.entity.WritingTemplate;
import com.example.genwriter.service.WritingTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 写作模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WritingTemplateServiceImpl implements WritingTemplateService {

    private final WritingTemplateMapper writingTemplateMapper;

    @Override
    @Transactional
    public WritingTemplateDTO createTemplate(CreateWritingTemplateRequest request) {
        log.info("创建写作模板: {}", request.getName());

        WritingTemplate template = WritingTemplate.builder()
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .category(request.getCategory())
                .content(request.getContent())
                .variables(request.getVariables())
                .example(request.getExample())
                .isSystem(request.getIsSystem() != null ? request.getIsSystem() : false)
                .usageCount(0)
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = writingTemplateMapper.insert(template);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(template);
    }

    @Override
    @Transactional(readOnly = true)
    public WritingTemplateDTO getTemplateById(String id) {
        WritingTemplate template = writingTemplateMapper.selectById(id);
        if (template == null) {
            throw new BizException(BizException.ErrorCode.TEMPLATE_NOT_FOUND);
        }
        return convertToDTO(template);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WritingTemplateDTO> getAllTemplates() {
        List<WritingTemplate> templates = writingTemplateMapper.selectAll();
        return templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WritingTemplateDTO> getTemplatesByType(String type) {
        List<WritingTemplate> templates = writingTemplateMapper.selectByType(type);
        return templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WritingTemplateDTO> getTemplatesByCategory(String category) {
        List<WritingTemplate> templates = writingTemplateMapper.selectByCategory(category);
        return templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WritingTemplateDTO> getSystemTemplates() {
        List<WritingTemplate> templates = writingTemplateMapper.selectSystemTemplates();
        return templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<WritingTemplateDTO> searchTemplatesByName(String name) {
        List<WritingTemplate> templates = writingTemplateMapper.selectByNameLike(name);
        return templates.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public WritingTemplateDTO updateTemplate(String id, UpdateWritingTemplateRequest request) {
        log.info("更新写作模板: {}", id);

        WritingTemplate existing = writingTemplateMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.TEMPLATE_NOT_FOUND);
        }

        WritingTemplate template = WritingTemplate.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .category(request.getCategory())
                .content(request.getContent())
                .variables(request.getVariables())
                .example(request.getExample())
                .isSystem(request.getIsSystem())
                .metadata(request.getMetadata())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = writingTemplateMapper.updateById(template);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getTemplateById(id);
    }

    @Override
    @Transactional
    public void deleteTemplate(String id) {
        log.info("删除写作模板: {}", id);

        WritingTemplate existing = writingTemplateMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.TEMPLATE_NOT_FOUND);
        }

        int result = writingTemplateMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public WritingTemplateDTO useTemplate(String id) {
        log.info("使用模板: {}", id);

        WritingTemplate existing = writingTemplateMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.TEMPLATE_NOT_FOUND);
        }

        int result = writingTemplateMapper.incrementUsageCount(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getTemplateById(id);
    }

    private WritingTemplateDTO convertToDTO(WritingTemplate template) {
        return WritingTemplateDTO.builder()
                .id(template.getId())
                .name(template.getName())
                .description(template.getDescription())
                .type(template.getType())
                .category(template.getCategory())
                .content(template.getContent())
                .variables(template.getVariables())
                .example(template.getExample())
                .isSystem(template.getIsSystem())
                .usageCount(template.getUsageCount())
                .metadata(template.getMetadata())
                .createdAt(template.getCreatedAt())
                .updatedAt(template.getUpdatedAt())
                .build();
    }
}
