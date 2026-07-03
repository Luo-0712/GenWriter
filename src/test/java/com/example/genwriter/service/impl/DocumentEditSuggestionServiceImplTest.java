package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.request.DocumentEditSuggestionRequest;
import com.example.genwriter.model.dto.response.DocumentDTO;
import com.example.genwriter.model.dto.response.DocumentEditSuggestionResponse;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.DocumentEditSuggestionMode;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.DocumentService;
import com.example.genwriter.service.LongTermMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentEditSuggestionServiceImplTest {

    @Mock
    private DocumentService documentService;

    @Mock
    private ChatClientFactory chatClientFactory;

    @Mock
    private LongTermMemoryService memoryService;

    @Mock
    private LongTermMemoryPromptFormatter memoryPromptFormatter;

    private ChatClient chatClient;
    private DocumentEditSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new DocumentEditSuggestionServiceImpl(
                documentService,
                chatClientFactory,
                memoryService,
                memoryPromptFormatter
        );
    }

    private void stubChatClient() {
        when(chatClientFactory.create(anyDouble())).thenReturn(chatClient);
    }

    @Test
    void suggestEdit_ShouldReturnSuggestionAndUseWritingMemories() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        List<MemoryVO> memories = List.of(MemoryVO.builder()
                .id("mem-1")
                .memoryType(MemoryType.WRITING_PREFERENCE.name())
                .content("偏好短句")
                .build());
        when(memoryService.retrieveMemories(anyString(), any(), eq("session-1"))).thenReturn(memories);
        when(memoryPromptFormatter.format(memories)).thenReturn("[长期记忆]\n偏好短句");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("润色后的片段");

        DocumentEditSuggestionResponse response = service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.POLISH_SELECTION));

        assertThat(response.getMode()).isEqualTo(DocumentEditSuggestionMode.POLISH_SELECTION);
        assertThat(response.getReplacementMarkdown()).isEqualTo("润色后的片段");
        assertThat(response.getSelectionFingerprint()).isEqualTo("fp-1");

        ArgumentCaptor<List<MemoryType>> typesCaptor = ArgumentCaptor.forClass(List.class);
        verify(memoryService).retrieveMemories(anyString(), typesCaptor.capture(), eq("session-1"));
        assertThat(typesCaptor.getValue())
                .containsExactly(MemoryType.WRITING_PREFERENCE, MemoryType.WRITING_TECHNIQUE);
    }

    @Test
    void suggestEdit_ShouldSupportRewriteMode() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        when(memoryService.retrieveMemories(anyString(), any(), anyString())).thenReturn(List.of());
        when(memoryPromptFormatter.format(List.of())).thenReturn("");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("重写后的片段");

        DocumentEditSuggestionResponse response = service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.REWRITE_SELECTION));

        assertThat(response.getMode()).isEqualTo(DocumentEditSuggestionMode.REWRITE_SELECTION);
        assertThat(response.getReplacementMarkdown()).isEqualTo("重写后的片段");
    }

    @Test
    void suggestEdit_ShouldSupportContinueMode() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        when(memoryService.retrieveMemories(anyString(), any(), anyString())).thenReturn(List.of());
        when(memoryPromptFormatter.format(List.of())).thenReturn("");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("续写片段");

        DocumentEditSuggestionResponse response = service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.CONTINUE_AFTER_SELECTION));

        assertThat(response.getMode()).isEqualTo(DocumentEditSuggestionMode.CONTINUE_AFTER_SELECTION);
        assertThat(response.getReplacementMarkdown()).isEqualTo("续写片段");
    }

    @Test
    void suggestEdit_ShouldCleanMarkdownFence() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        when(memoryService.retrieveMemories(anyString(), any(), anyString())).thenReturn(List.of());
        when(memoryPromptFormatter.format(List.of())).thenReturn("");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("```markdown\n清洗后的片段\n```");

        DocumentEditSuggestionResponse response = service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.POLISH_SELECTION));

        assertThat(response.getReplacementMarkdown()).isEqualTo("清洗后的片段");
    }

    @Test
    void suggestEdit_WhenDocumentMissing_ShouldPropagateDocumentNotFound() {
        when(documentService.getDocumentById("missing"))
                .thenThrow(new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND));

        assertThatThrownBy(() -> service.suggestEdit("missing", request(DocumentEditSuggestionMode.POLISH_SELECTION)))
                .isInstanceOf(BizException.class)
                .hasMessage("文档不存在");
    }

    @Test
    void suggestEdit_WhenModelReturnsBlank_ShouldThrowAiServiceError() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        when(memoryService.retrieveMemories(anyString(), any(), anyString())).thenReturn(List.of());
        when(memoryPromptFormatter.format(List.of())).thenReturn("");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("   ");

        assertThatThrownBy(() -> service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.POLISH_SELECTION)))
                .isInstanceOf(BizException.class)
                .hasMessage("AI服务调用失败");
    }

    @Test
    void suggestEdit_WhenModelReturnsOversizedText_ShouldThrowAiServiceError() {
        stubChatClient();
        when(documentService.getDocumentById("doc-1")).thenReturn(document());
        when(memoryService.retrieveMemories(anyString(), any(), anyString())).thenReturn(List.of());
        when(memoryPromptFormatter.format(List.of())).thenReturn("");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("x".repeat(13000));

        assertThatThrownBy(() -> service.suggestEdit("doc-1", request(DocumentEditSuggestionMode.POLISH_SELECTION)))
                .isInstanceOf(BizException.class)
                .hasMessage("AI服务调用失败");
    }

    private DocumentDTO document() {
        return DocumentDTO.builder()
                .id("doc-1")
                .sessionId("session-1")
                .title("测试文稿")
                .content("前文 原文 后文")
                .version(2)
                .build();
    }

    private DocumentEditSuggestionRequest request(DocumentEditSuggestionMode mode) {
        return DocumentEditSuggestionRequest.builder()
                .mode(mode)
                .instruction("更好")
                .title("测试文稿")
                .selectedText("原文")
                .selectedMarkdown("原文")
                .beforeText("前文")
                .afterText("后文")
                .selectionFingerprint("fp-1")
                .clientDocumentVersion(2)
                .build();
    }
}
