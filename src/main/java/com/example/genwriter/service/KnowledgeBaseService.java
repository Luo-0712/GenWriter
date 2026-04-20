package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateKnowledgeBaseRequest;
import com.example.genwriter.model.dto.request.UpdateKnowledgeBaseRequest;
import com.example.genwriter.model.dto.response.KnowledgeBaseDTO;

import java.util.List;

/**
 * 知识库服务接口
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库
     */
    KnowledgeBaseDTO createKnowledgeBase(CreateKnowledgeBaseRequest request);

    /**
     * 根据ID查询知识库
     */
    KnowledgeBaseDTO getKnowledgeBaseById(String id);

    /**
     * 查询所有知识库
     */
    List<KnowledgeBaseDTO> getAllKnowledgeBases();

    /**
     * 根据类型查询知识库
     */
    List<KnowledgeBaseDTO> getKnowledgeBasesByType(String type);

    /**
     * 根据名称搜索知识库
     */
    List<KnowledgeBaseDTO> searchKnowledgeBasesByName(String name);

    /**
     * 更新知识库
     */
    KnowledgeBaseDTO updateKnowledgeBase(String id, UpdateKnowledgeBaseRequest request);

    /**
     * 删除知识库
     */
    void deleteKnowledgeBase(String id);

    /**
     * 批量删除知识库
     */
    void deleteKnowledgeBases(List<String> ids);
}
