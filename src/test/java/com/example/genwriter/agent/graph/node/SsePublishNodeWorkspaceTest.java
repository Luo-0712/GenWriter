package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.example.genwriter.agent.streaming.ContentStreamPublisher;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.Candidate;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.WritingTarget;
import com.example.genwriter.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SsePublishNodeWorkspaceTest {
    private final SseService sse = mock(SseService.class);
    private final ContentStreamPublisher stream = mock(ContentStreamPublisher.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private SsePublishNode node;

    @BeforeEach
    void setup() {
        node = new SsePublishNode(sse, stream, json, documents, mock(WritingOutputSettingsService.class), mock(TitleGenerationService.class), workspace);
    }

    @Test
    void workspaceWritingPublishesACandidateWithoutCreatingAnAcceptedVersion() throws Exception {
        String project = UUID.randomUUID().toString(), targetNode = UUID.randomUUID().toString();
        WritingTarget target = new WritingTarget(project, targetNode, "REPLACE", null, null, null);
        Candidate candidate = new Candidate("candidate", project, "session", "session", targetNode, null, null, "REPLACE", null,
                "正文", "候选正文", "修改", "markdown", "PENDING", null, null, OffsetDateTime.now());
        when(workspace.createCandidate(eq("session"), eq(target), anyString(), eq("候选正文"), eq("修改"), eq("markdown"))).thenReturn(candidate);

        node.apply(state(target));

        verifyNoInteractions(documents);
        verify(stream).publishFinal(eq("session"), eq("候选正文"), isNull(), isNull(), nullable(String.class), eq(candidate));
        verify(sse).complete("session");
    }

    @Test
    void freeDiscussionDoesNotCreateACandidateOrSnapshot() throws Exception {
        node.apply(state(new WritingTarget("project", "node", "DISCUSS", null, null, null)));
        verifyNoInteractions(workspace, documents);
        verify(sse).complete("session");
    }

    @Test
    void failedCandidatePersistenceDoesNotSendASuccessfulFinalOrCompletion() {
        when(workspace.createCandidate(anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> node.apply(state(new WritingTarget("project", "node", "REPLACE", null, null, null))));
        verifyNoInteractions(documents, stream);
        verify(sse, never()).complete(anyString());
    }

    private OverAllState state(WritingTarget target) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sessionId", "session");
        values.put("finalOutput", "候选正文");
        values.put("userInput", "修改");
        values.put("writingType", "CREATE");
        values.put("outputFormat", "markdown");
        values.put("writingTarget", json.convertValue(target, Map.class));
        return new OverAllState(values);
    }
}
