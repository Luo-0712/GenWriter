package com.example.genwriter.controller;

import com.example.genwriter.model.dto.response.WritingOutputSettings;
import com.example.genwriter.service.WritingOutputSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettingsController.class)
class SettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WritingOutputSettingsService writingOutputSettingsService;

    @Test
    void getWritingOutputSettings_ShouldReturnCurrentSettings() throws Exception {
        when(writingOutputSettingsService.getSettings())
                .thenReturn(new WritingOutputSettings(true, "markdown"));

        mockMvc.perform(get("/api/settings/writing-output"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.markdownEnabled").value(true))
                .andExpect(jsonPath("$.data.format").value("markdown"));
    }

    @Test
    void updateWritingOutputSettings_ShouldPersistRuntimeValue() throws Exception {
        when(writingOutputSettingsService.updateMarkdownEnabled(false))
                .thenReturn(new WritingOutputSettings(false, "plain"));

        mockMvc.perform(put("/api/settings/writing-output")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("markdownEnabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.markdownEnabled").value(false))
                .andExpect(jsonPath("$.data.format").value("plain"));
    }
}
