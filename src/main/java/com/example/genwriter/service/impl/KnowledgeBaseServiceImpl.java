package com.example.genwriter.service.impl;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.mapper.KnowledgeBaseMapper;
import com.example.genwriter.mapper.KnowledgeChunkMapper;
import com.example.genwriter.model.dto.request.CreateKnowledgeBaseRequest;
import com.example.genwriter.model.dto.request.UpdateKnowledgeBaseRequest;
import com.example.genwriter.model.dto.response.KnowledgeBaseDTO;
import com.example.genwriter.model.entity.KnowledgeBase;
import com.example.genwriter.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 知识库服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    @Override
    @Transactional
    public KnowledgeBaseDTO createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        log.info("创建知识库: {}", request.getName());

        KnowledgeBase kb = KnowledgeBase.builder()
                .name(request.getName())
                .description(request.getDescription())
                .type(StringUtils.hasText(request.getType()) ? request.getType() : "reference")
                .metadata(request.getMetadata())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = knowledgeBaseMapper.insert(kb);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_INSERT_ERROR);
        }

        return convertToDTO(kb);
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeBaseDTO getKnowledgeBaseById(String id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return convertToDTOWithStats(kb);
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseDTO> getAllKnowledgeBases() {
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectAll();
        return kbs.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseDTO> getKnowledgeBasesByType(String type) {
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectByType(type);
        return kbs.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBaseDTO> searchKnowledgeBasesByName(String name) {
        List<KnowledgeBase> kbs = knowledgeBaseMapper.selectByNameLike(name);
        return kbs.stream()
                .map(this::convertToDTOWithStats)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public KnowledgeBaseDTO updateKnowledgeBase(String id, UpdateKnowledgeBaseRequest request) {
        log.info("更新知识库: {}", id);

        KnowledgeBase existing = knowledgeBaseMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        KnowledgeBase kb = KnowledgeBase.builder()
                .id(id)
                .name(request.getName())
                .description(request.getDescription())
                .type(request.getType())
                .metadata(request.getMetadata())
                .updatedAt(LocalDateTime.now())
                .build();

        int result = knowledgeBaseMapper.updateById(kb);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_UPDATE_ERROR);
        }

        return getKnowledgeBaseById(id);
    }

    @Override
    @Transactional
    public void deleteKnowledgeBase(String id) {
        log.info("删除知识库: {}", id);

        KnowledgeBase existing = knowledgeBaseMapper.selectById(id);
        if (existing == null) {
            throw new BizException(BizException.ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }

        int result = knowledgeBaseMapper.deleteById(id);
        if (result <= 0) {
            throw new BizException(BizException.ErrorCode.DB_DELETE_ERROR);
        }
    }

    @Override
    @Transactional
    public void deleteKnowledgeBases(List<String> ids) {
        log.info("批量删除知识库: {}", ids);

        if (ids == null || ids.isEmpty()) {
            return;
        }

        int result = knowledgeBaseMapper.deleteByIds(ids);
        log.info("成功删除 {} 个知识库", result);
    }

    private KnowledgeBaseDTO convertToDTO(KnowledgeBase kb) {
        return KnowledgeBaseDTO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .type(kb.getType())
                .metadata(kb.getMetadata())
                .createdAt(kb.getCreatedAt())
                .updatedAt(kb.getUpdatedAt())
                .build();
    }

    private KnowledgeBaseDTO convertToDTOWithStats(KnowledgeBase kb) {
        KnowledgeBaseDTO dto = convertToDTO(kb);
        dto.setChunkCount(knowledgeChunkMapper.countByKbId(kb.getId()));
        return dto;
    }
}
