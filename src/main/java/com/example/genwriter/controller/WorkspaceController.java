package com.example.genwriter.controller;

import com.example.genwriter.model.common.ApiResponse;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.*;
import com.example.genwriter.service.DocumentExportService;
import com.example.genwriter.service.ProjectService;
import com.example.genwriter.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/workspace")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspace;
    private final ProjectService projects;
    private final DocumentExportService exports;

    @GetMapping
    public ApiResponse<Workspace> get(@PathVariable String projectId) { return ApiResponse.success(workspace.getWorkspace(projectId)); }

    @PostMapping("/nodes")
    public ApiResponse<ContentNode> create(@PathVariable String projectId, @Valid @RequestBody NodeRequest request) {
        return ApiResponse.success(workspace.createNode(projectId, request));
    }

    @PutMapping("/nodes/{nodeId}")
    public ApiResponse<ContentNode> rename(@PathVariable String projectId, @PathVariable String nodeId, @Valid @RequestBody NodeRequest request) {
        return ApiResponse.success(workspace.renameNode(projectId, nodeId, request));
    }

    @PostMapping("/nodes/{nodeId}/move")
    public ApiResponse<ContentNode> move(@PathVariable String projectId, @PathVariable String nodeId, @Valid @RequestBody MoveRequest request) {
        return ApiResponse.success(workspace.moveNode(projectId, nodeId, request));
    }

    @DeleteMapping("/nodes/{nodeId}")
    public ApiResponse<Void> delete(@PathVariable String projectId, @PathVariable String nodeId) {
        workspace.deleteNode(projectId, nodeId);
        return ApiResponse.success(null);
    }

    @GetMapping("/nodes/{nodeId}/versions")
    public ApiResponse<List<DocumentDTO>> versions(@PathVariable String projectId, @PathVariable String nodeId) {
        return ApiResponse.success(workspace.versions(projectId, nodeId));
    }

    @PostMapping("/nodes/{nodeId}/versions")
    public ApiResponse<DocumentDTO> save(@PathVariable String projectId, @PathVariable String nodeId, @Valid @RequestBody SaveRequest request) {
        return ApiResponse.success(workspace.saveSnapshot(projectId, nodeId, request));
    }

    @GetMapping("/legacy")
    public ApiResponse<List<DocumentDTO>> legacy(@PathVariable String projectId) { return ApiResponse.success(workspace.legacyDocuments(projectId)); }

    @PostMapping("/legacy/import")
    public ApiResponse<Adoption> importDocument(@PathVariable String projectId, @Valid @RequestBody ImportRequest request) {
        return ApiResponse.success(workspace.importLegacy(projectId, request));
    }

    @GetMapping("/candidates")
    public ApiResponse<List<Candidate>> candidates(@PathVariable String projectId) { return ApiResponse.success(workspace.candidates(projectId)); }

    @PostMapping("/candidates")
    public ApiResponse<Candidate> propose(@PathVariable String projectId, @Valid @RequestBody ProposalRequest request) {
        return ApiResponse.success(workspace.proposeSnapshot(projectId, request));
    }

    @PostMapping("/candidates/{candidateId}/adopt")
    public ApiResponse<Adoption> adopt(@PathVariable String projectId, @PathVariable String candidateId) {
        return ApiResponse.success(workspace.adopt(projectId, candidateId));
    }

    @GetMapping("/reading")
    public ApiResponse<Map<String, String>> reading(@PathVariable String projectId) {
        return ApiResponse.success(Map.of("title", projects.getProjectById(projectId).getName(), "content", workspace.wholeContent(projectId)));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable String projectId, @RequestParam(defaultValue = "markdown") String format) {
        if (!List.of("markdown", "docx").contains(format)) throw new IllegalArgumentException("仅支持 Markdown 或 Word 导出");
        String title = projects.getProjectById(projectId).getName();
        String content = workspace.wholeContent(projectId);
        boolean word = format.equals("docx");
        byte[] bytes = word ? exports.exportAsDocx(title, content) : exports.exportAsMarkdown(title, content);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(title + (word ? ".docx" : ".md"), StandardCharsets.UTF_8).build().toString())
                .contentType(word ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document") : MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(bytes);
    }
}
