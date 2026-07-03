package com.example.genwriter.controller;

import com.example.genwriter.exception.BizException;
import com.example.genwriter.model.dto.request.DocumentEditSuggestionRequest;
import com.example.genwriter.model.dto.response.DocumentEditSuggestionResponse;
import com.example.genwriter.model.enums.DocumentEditSuggestionMode;
import com.example.genwriter.service.DocumentEditSuggestionService;
import com.example.genwriter.service.DocumentExportService;
import com.example.genwriter.service.DocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private DocumentExportService documentExportService;

    @MockitoBean
    private DocumentEditSuggestionService documentEditSuggestionService;

    @Test
    void suggestEdit_ShouldReturnSuggestion() throws Exception {
        DocumentEditSuggestionRequest request = validRequest();
        DocumentEditSuggestionResponse response = DocumentEditSuggestionResponse.builder()
                .mode(DocumentEditSuggestionMode.POLISH_SELECTION)
                .replacementMarkdown("润色后的片段")
                .selectionFingerprint("fp-1")
                .createdAt(LocalDateTime.now())
                .build();
        when(documentEditSuggestionService.suggestEdit(eq("doc-1"), any())).thenReturn(response);

        mockMvc.perform(post("/api/documents/doc-1/edit-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.mode").value("POLISH_SELECTION"))
                .andExpect(jsonPath("$.data.replacementMarkdown").value("润色后的片段"))
                .andExpect(jsonPath("$.data.selectionFingerprint").value("fp-1"));
    }

    @Test
    void suggestEdit_WithBlankSelectedText_ShouldReturnBadRequest() throws Exception {
        DocumentEditSuggestionRequest request = validRequest();
        request.setSelectedText("");

        mockMvc.perform(post("/api/documents/doc-1/edit-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void suggestEdit_WithBlankSelectedMarkdown_ShouldReturnBadRequest() throws Exception {
        DocumentEditSuggestionRequest request = validRequest();
        request.setSelectedMarkdown("");

        mockMvc.perform(post("/api/documents/doc-1/edit-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void suggestEdit_WithMissingMode_ShouldReturnBadRequest() throws Exception {
        DocumentEditSuggestionRequest request = validRequest();
        request.setMode(null);

        mockMvc.perform(post("/api/documents/doc-1/edit-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    void suggestEdit_WhenDocumentMissing_ShouldReturnBusinessError() throws Exception {
        when(documentEditSuggestionService.suggestEdit(eq("missing"), any()))
                .thenThrow(new BizException(BizException.ErrorCode.DOCUMENT_NOT_FOUND));

        mockMvc.perform(post("/api/documents/missing/edit-suggestion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("1004"));
    }

    private DocumentEditSuggestionRequest validRequest() {
        return DocumentEditSuggestionRequest.builder()
                .mode(DocumentEditSuggestionMode.POLISH_SELECTION)
                .instruction("更顺畅")
                .title("标题")
                .selectedText("原文")
                .selectedMarkdown("原文")
                .beforeText("前文")
                .afterText("后文")
                .selectionFingerprint("fp-1")
                .clientDocumentVersion(1)
                .build();
    }
}
