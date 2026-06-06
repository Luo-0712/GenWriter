package com.example.genwriter.service;

import com.example.genwriter.model.dto.request.CreateMemoryRequest;
import com.example.genwriter.model.dto.request.UpdateMemoryRequest;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.dto.response.PageResult;
import com.example.genwriter.model.entity.LongTermMemory;
import com.example.genwriter.model.enums.MemoryType;

import java.util.List;
import java.util.Map;

public interface LongTermMemoryService {

    List<MemoryVO> retrieveMemories(String query, List<MemoryType> types,
                                    String sessionId);

    LongTermMemory storeMemory(String content, MemoryType type, String scope,
                               String projectId,
                               String sessionId, String importance);

    LongTermMemory storeMemory(String content, MemoryType type, String scope,
                               String projectId,
                               String sessionId, String importance,
                               Map<String, Object> metadata);

    void deleteMemory(String id);

    int deleteMemories(List<String> ids);

    MemoryVO getById(String id);

    PageResult<MemoryVO> listByFilter(String type, String scope, String projectId,
                                      String importance,
                                      String keyword, int page, int size);

    List<MemoryVO> searchByQuery(String query, List<String> types, String scope,
                                 String projectId, double threshold, int limit);

    MemoryVO createMemory(CreateMemoryRequest request);

    MemoryVO updateMemory(String id, UpdateMemoryRequest request);

    List<MemoryVO> listByType(MemoryType type);

    List<MemoryVO> listByProject(String projectId);
}
