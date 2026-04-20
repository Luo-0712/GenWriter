package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateWritingTemplateRequest;
import com.example.genwriter.model.dto.request.UpdateWritingTemplateRequest;
import com.example.genwriter.model.dto.response.WritingTemplateDTO;

import java.util.List;

/**
 * 写作模板服务接口
 */
public interface WritingTemplateService {

    /**
     * 创建模板
     */
    WritingTemplateDTO createTemplate(CreateWritingTemplateRequest request);

    /**
     * 根据ID查询模板
     */
    WritingTemplateDTO getTemplateById(String id);

    /**
     * 查询所有模板
     */
    List<WritingTemplateDTO> getAllTemplates();

    /**
     * 根据类型查询模板
     */
    List<WritingTemplateDTO> getTemplatesByType(String type);

    /**
     * 根据分类查询模板
     */
    List<WritingTemplateDTO> getTemplatesByCategory(String category);

    /**
     * 查询系统模板
     */
    List<WritingTemplateDTO> getSystemTemplates();

    /**
     * 根据名称搜索模板
     */
    List<WritingTemplateDTO> searchTemplatesByName(String name);

    /**
     * 更新模板
     */
    WritingTemplateDTO updateTemplate(String id, UpdateWritingTemplateRequest request);

    /**
     * 删除模板
     */
    void deleteTemplate(String id);

    /**
     * 使用模板(增加使用次数)
     */
    WritingTemplateDTO useTemplate(String id);
}
