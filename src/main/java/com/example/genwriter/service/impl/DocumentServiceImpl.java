package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.DocumentMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateDocumentRequest;
import com.example.genwriter.model.dto.request.UpdateDocumentRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.entity.Document;
import com.example.genwriter.model.entity.TaskSession;
import com.example.genwriter.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final TaskSessionMapper taskSessionMapper;

    @Override
    @Transactional
    public DocumentDTO createDocument(CreateDocumentRequest request) {
        log.info("创建文档: sessionId={}, title={}", request.getSessionId(), request.getTitle());

        TaskSession session = taskSessionMapper.selectById(request.getSessionId());
        if (session == null) {
            throw new BizException(BizException.ErrorCode.SESSION_NOT_FOUND);
        }

        Document document = Document.builder()
                .sessionId(request.getSessionId())
                .title(request.getTitle())
                .type(StringUtils.hasText(request.getType()) ? request.getType() : "draft")
                .content(request.getContent())
                .format(StringUtils.hasText(request.getFormat()) ? request.getFormat() : "markdown")
                .version(1)
                .status(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "editing")
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = documentMapper.insert(document);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(document);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDTO getDocumentById(String id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return convertToDTO(document);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentDTO> getDocumentsBySessionId(String sessionId) {
        List<Document> documents = documentMapper.selectBySessionId(sessionId);
        return documents.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDTO getLatestDocumentBySessionId(String sessionId) {
        Document document = documentMapper.selectLatestBySessionId(sessionId);
        if (document == null) {
            throw new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return convertToDTO(document);
    }

    @Override
    @Transactional
    public DocumentDTO createNewVersion(String sessionId, CreateDocumentRequest request) {
        log.info("创建文档新版本: sessionId={}", sessionId);

        int maxVersion = documentMapper.getMaxVersionBySessionId(sessionId);

        Document document = Document.builder()
                .sessionId(sessionId)
                .title(request.getTitle())
                .type(StringUtils.hasText(request.getType()) ? request.getType() : "draft")
                .content(request.getContent())
                .format(StringUtils.hasText(request.getFormat()) ? request.getFormat() : "markdown")
                .version(maxVersion + 1)
                .status(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "editing")
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = documentMapper.insert(document);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(document);
    }

    @Override
    @Transactional
    public DocumentDTO updateDocument(String id, UpdateDocumentRequest request) {
        log.info("更新文档: {}", id);

        Document existing = documentMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND);
        }

        Document document = Document.builder()
                .id(id)
                .title(request.getTitle())
                .type(request.getType())
                .content(request.getContent())
                .format(request.getFormat())
                .status(request.getStatus())
                .metadata(request.getMetadata())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = documentMapper.updateById(document);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getDocumentById(id);
    }

    @Override
    @Transactional
    public void deleteDocument(String id) {
        log.info("删除文档: {}", id);

        Document existing = documentMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND);
        }

        int result = documentMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentDTO> getDocumentsByStatus(String status) {
        List<Document> documents = documentMapper.selectByStatus(status);
        return documents.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private DocumentDTO convertToDTO(Document document) {
        return DocumentDTO.builder()
                .id(document.getId())
                .sessionId(document.getSessionId())
                .title(document.getTitle())
                .type(document.getType())
                .content(document.getContent())
                .format(document.getFormat())
                .version(document.getVersion())
                .status(document.getStatus())
                .metadata(document.getMetadata())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
