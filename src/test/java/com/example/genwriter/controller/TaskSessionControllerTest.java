package com.example.genwriter.controller;

import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.request.UpdateTaskSessionRequest;
import com.example.genwriter.model.dto.response.TaskSessionDTO;
import com.example.genwriter.service.TaskSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 任务会话控制器单元测试
 */
@WebMvcTest(TaskSessionController.class)
class TaskSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskSessionService taskSessionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createSession_ShouldReturnCreatedSession() throws Exception {
        // Given
        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("测试会话")
                .type("writing")
                .build();

        TaskSessionDTO dto = TaskSessionDTO.builder()
                .id("test-uuid")
                .title("测试会话")
                .type("writing")
                .status("active")
                .createdAt(LocalDateTime.now())
                .build();

        when(taskSessionService.createSession(any())).thenReturn(dto);

        // When & Then
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.title").value("测试会话"));
    }

    @Test
    void getSession_ShouldReturnSession() throws Exception {
        // Given
        TaskSessionDTO dto = TaskSessionDTO.builder()
                .id("test-uuid")
                .title("测试会话")
                .status("active")
                .build();

        when(taskSessionService.getSessionById("test-uuid")).thenReturn(dto);

        // When & Then
        mockMvc.perform(get("/api/sessions/test-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.id").value("test-uuid"));
    }

    @Test
    void getAllSessions_ShouldReturnSessionList() throws Exception {
        // Given
        List<TaskSessionDTO> dtos = Arrays.asList(
                TaskSessionDTO.builder().id("1").title("会话1").build(),
                TaskSessionDTO.builder().id("2").title("会话2").build()
        );

        when(taskSessionService.getAllSessions()).thenReturn(dtos);

        // When & Then
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void updateSession_ShouldReturnUpdatedSession() throws Exception {
        // Given
        UpdateTaskSessionRequest request = UpdateTaskSessionRequest.builder()
                .title("更新后的标题")
                .build();

        TaskSessionDTO dto = TaskSessionDTO.builder()
                .id("test-uuid")
                .title("更新后的标题")
                .build();

        when(taskSessionService.updateSession(any(), any())).thenReturn(dto);

        // When & Then
        mockMvc.perform(put("/api/sessions/test-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    void deleteSession_ShouldReturnSuccess() throws Exception {
        // Given
        doNothing().when(taskSessionService).deleteSession("test-uuid");

        // When & Then
        mockMvc.perform(delete("/api/sessions/test-uuid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    void createSession_WithInvalidRequest_ShouldReturnBadRequest() throws Exception {
        // Given - 标题为空
        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("")
                .build();

        // When & Then
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
