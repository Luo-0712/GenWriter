package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateKnowledgeChunkRequest;
import com.example.genwriter.model.dto.request.SearchKnowledgeChunkRequest;
import com.example.genwriter.model.dto.response.KnowledgeChunkDTO;

import java.util.List;

/**
 * 知识片段服务接口
 */
public interface KnowledgeChunkService {

    /**
     * 创建知识片段
     */
    KnowledgeChunkDTO createChunk(CreateKnowledgeChunkRequest request);

    /**
     * 批量创建知识片段
     */
    List<KnowledgeChunkDTO> createChunks(List<CreateKnowledgeChunkRequest> requests);

    /**
     * 根据ID查询知识片段
     */
    KnowledgeChunkDTO getChunkById(String id);

    /**
     * 根据知识库ID查询所有片段
     */
    List<KnowledgeChunkDTO> getChunksByKbId(String kbId);

    /**
     * 相似度搜索
     */
    List<KnowledgeChunkDTO> searchSimilarChunks(SearchKnowledgeChunkRequest request);

    /**
     * 删除知识片段
     */
    void deleteChunk(String id);

    /**
     * 删除知识库的所有片段
     */
    void deleteChunksByKbId(String kbId);

    /**
     * 根据源文档ID删除片段
     */
    void deleteChunksBySourceId(String sourceId);
}
