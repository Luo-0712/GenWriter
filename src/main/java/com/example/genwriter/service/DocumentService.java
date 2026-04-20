package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateDocumentRequest;
import com.example.genwriter.model.dto.request.UpdateDocumentRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;

import java.util.List;

/**
 * 文档服务接口
 */
public interface DocumentService {

    /**
     * 创建文档
     */
    DocumentDTO createDocument(CreateDocumentRequest request);

    /**
     * 根据ID查询文档
     */
    DocumentDTO getDocumentById(String id);

    /**
     * 根据会话ID查询文档列表
     */
    List<DocumentDTO> getDocumentsBySessionId(String sessionId);

    /**
     * 查询会话的最新版本文档
     */
    DocumentDTO getLatestDocumentBySessionId(String sessionId);

    /**
     * 创建文档新版本
     */
    DocumentDTO createNewVersion(String sessionId, CreateDocumentRequest request);

    /**
     * 更新文档
     */
    DocumentDTO updateDocument(String id, UpdateDocumentRequest request);

    /**
     * 删除文档
     */
    void deleteDocument(String id);

    /**
     * 根据状态查询文档
     */
    List<DocumentDTO> getDocumentsByStatus(String status);
}
