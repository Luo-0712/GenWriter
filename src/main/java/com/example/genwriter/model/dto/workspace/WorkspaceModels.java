package com.example.genwriter.model.dto.workspace;

import com.example.genwriter.model.dto.response.DocumentDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class WorkspaceModels {
    private WorkspaceModels() {}

    public record ContentNode(String id, String projectId, String parentId, String title, int position,
                              boolean root, String currentDocumentId, int version, String summary) {}
    public record Workspace(String projectId, String rootId, List<ContentNode> nodes) {}
    public record NodeRequest(@NotBlank @Size(max = 500) String title, String parentId, Integer position) {}
    public record MoveRequest(@NotBlank String parentId, @NotNull Integer position) {}
    public record SaveRequest(@NotBlank @Size(max = 500) String title, @NotNull String content,
                              String baseDocumentId, String sourceSessionId) {}
    public record ImportRequest(@NotBlank String documentId, String parentId) {}
    public record ProposalRequest(@NotBlank String nodeId, String baseDocumentId, @NotBlank String title,
                                  @NotNull String content, String instruction, String sourceSessionId) {}
    public record WritingTarget(String projectId, String nodeId, String operation, String parentId,
                                String afterNodeId, String baseDocumentId) {}
    public record Candidate(String id, String projectId, String sessionId, String sourceSessionId,
                            String targetNodeId, String parentNodeId, String afterNodeId, String operation,
                            String baseDocumentId, String title, String content, String instruction,
                            String format, String status, String acceptedDocumentId, String acceptedNodeId,
                            OffsetDateTime createdAt) {}
    public record Adoption(ContentNode node, DocumentDTO document, Candidate candidate) {}
    public record SnapshotSaved(String projectId, String nodeId, String documentId, String sessionId,
                                String title, String content, String instruction) {}
}
