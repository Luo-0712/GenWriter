package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.request.CreateMemoryRequest;
import com.example.genwriter.model.dto.request.UpdateMemoryRequest;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.dto.response.PageResult;
import com.example.genwriter.service.LongTermMemoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/long-term-memories")
@RequiredArgsConstructor
public class LongTermMemoryController {

    private final LongTermMemoryService memoryService;

    @GetMapping
    public ApiResponse<PageResult<MemoryVO>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String importance,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<MemoryVO> result = memoryService.listByFilter(
                type, scope, projectId, importance, keyword, page, size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    public ApiResponse<MemoryVO> getById(@PathVariable String id) {
        MemoryVO vo = memoryService.getById(id);
        return ApiResponse.success(vo);
    }

    @PostMapping
    public ApiResponse<MemoryVO> create(@Valid @RequestBody CreateMemoryRequest request) {
        MemoryVO vo = memoryService.createMemory(request);
        return ApiResponse.success(vo);
    }

    @PutMapping("/{id}")
    public ApiResponse<MemoryVO> update(@PathVariable String id,
                                        @RequestBody UpdateMemoryRequest request) {
        MemoryVO vo = memoryService.updateMemory(id, request);
        return ApiResponse.success(vo);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        memoryService.deleteMemory(id);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Integer> batchDelete(@RequestBody List<String> ids) {
        int count = memoryService.deleteMemories(ids);
        return ApiResponse.success(count);
    }

    @GetMapping("/search")
    public ApiResponse<List<MemoryVO>> search(
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) Double threshold,
            @RequestParam(defaultValue = "10") int limit) {
        List<String> types = type != null ? Arrays.asList(type.split(",")) : null;
        List<MemoryVO> results = memoryService.searchByQuery(
                q, types, scope, projectId,
                threshold != null ? threshold : 0.0,
                limit);
        return ApiResponse.success(results);
    }
}
