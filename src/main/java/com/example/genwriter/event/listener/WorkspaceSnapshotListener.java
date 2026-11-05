package com.example.genwriter.event.listener;

import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.SnapshotSaved;
import com.example.genwriter.service.ChapterSummaryService;
import com.example.genwriter.service.MemoryExtractionService;
import com.example.genwriter.service.SettingMemoryExtractionService;
import com.example.genwriter.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Derived continuity information is updated only after an accepted snapshot commits. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkspaceSnapshotListener {
    private final WorkspaceService workspace;
    private final ChapterSummaryService summaries;
    private final MemoryExtractionService memories;
    private final SettingMemoryExtractionService settings;
    private final LongTermMemoryProperties properties;

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void saved(SnapshotSaved event) {
        try {
            if (!workspace.isCurrentSnapshot(event.nodeId(), event.documentId())) return;
            String summary = summaries.summarize(event.instruction(), event.content(), "WORKSPACE");
            workspace.storeSummary(event.nodeId(), event.documentId(), summary);
            if (properties.isEnabled() && event.sessionId() != null && workspace.isCurrentSnapshot(event.nodeId(), event.documentId())) {
                memories.extractForProjectAsync(event.projectId(), event.sessionId(), event.instruction(), event.content());
                settings.extractForProjectAsync(event.projectId(), event.sessionId(), event.instruction(), event.content());
            }
        } catch (Exception e) {
            // Summary generation must never roll back, replace or hide the user's saved text.
            log.warn("作品摘要更新失败，正文快照已保留: nodeId={}, documentId={}", event.nodeId(), event.documentId(), e);
        }
    }
}
