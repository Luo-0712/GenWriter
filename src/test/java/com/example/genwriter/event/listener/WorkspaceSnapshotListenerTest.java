package com.example.genwriter.event.listener;

import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.model.dto.workspace.WorkspaceModels.SnapshotSaved;
import com.example.genwriter.service.*;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceSnapshotListenerTest {
    @Test
    void acceptedFactsKeepTheirProjectEvenIfTheirSourceSessionWasDeleted() {
        WorkspaceService workspace = mock(WorkspaceService.class);
        ChapterSummaryService summaries = mock(ChapterSummaryService.class);
        MemoryExtractionService memories = mock(MemoryExtractionService.class);
        SettingMemoryExtractionService settings = mock(SettingMemoryExtractionService.class);
        LongTermMemoryProperties properties = new LongTermMemoryProperties();
        properties.setEnabled(true);
        when(workspace.isCurrentSnapshot("node", "snapshot")).thenReturn(true);
        when(summaries.summarize(anyString(), anyString(), anyString())).thenReturn("已保存的章节摘要");
        var listener = new WorkspaceSnapshotListener(workspace, summaries, memories, settings, properties);

        listener.saved(new SnapshotSaved("book-project", "node", "snapshot", "deleted-source-session", "正文", "已采纳正文", "采纳要求"));

        verify(memories).extractForProjectAsync("book-project", "deleted-source-session", "采纳要求", "已采纳正文");
        verify(settings).extractForProjectAsync("book-project", "deleted-source-session", "采纳要求", "已采纳正文");
        verify(memories, never()).extractAsync(anyString(), anyString(), anyString());
        verify(settings, never()).extractAsync(anyString(), anyString(), anyString());
    }

    @Test
    void anOutdatedSnapshotDoesNotRefreshCanonicalFacts() {
        WorkspaceService workspace = mock(WorkspaceService.class);
        ChapterSummaryService summaries = mock(ChapterSummaryService.class);
        MemoryExtractionService memories = mock(MemoryExtractionService.class);
        SettingMemoryExtractionService settings = mock(SettingMemoryExtractionService.class);
        var listener = new WorkspaceSnapshotListener(workspace, summaries, memories, settings, new LongTermMemoryProperties());
        listener.saved(new SnapshotSaved("project", "node", "old-snapshot", "session", "正文", "旧正文", "旧要求"));
        verifyNoInteractions(summaries, memories, settings);
    }
}
