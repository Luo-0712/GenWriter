package com.example.genwriter.service.impl;

import com.example.genwriter.mapper.DocumentMapper;
import com.example.genwriter.mapper.MessageMapper;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.request.UpdateTaskSessionRequest;
import com.example.genwriter.model.dto.response.TaskSessionDTO;
import com.example.genwriter.model.entity.TaskSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 任务会话服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class TaskSessionServiceImplTest {

    @Mock
    private TaskSessionMapper taskSessionMapper;

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private DocumentMapper documentMapper;

    @InjectMocks
    private TaskSessionServiceImpl taskSessionService;

    private TaskSession mockSession;

    @BeforeEach
    void setUp() {
        mockSession = TaskSession.builder()
                .id("test-uuid")
                .title("测试会话")
                .type("writing")
                .status("active")
                .topic("测试主题")
                .style("formal")
                .metadata("{}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void createSession_ShouldReturnDTO() {
        // Given
        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("测试会话")
                .type("writing")
                .topic("测试主题")
                .build();

        when(taskSessionMapper.insert(any(TaskSession.class))).thenReturn(1);

        // When
        TaskSessionDTO result = taskSessionService.createSession(request);

        // Then
        assertNotNull(result);
        assertEquals("测试会话", result.getTitle());
        assertEquals("writing", result.getType());
        assertEquals("active", result.getStatus());
        verify(taskSessionMapper, times(1)).insert(any(TaskSession.class));
    }

    @Test
    void getSessionById_ShouldReturnDTO() {
        // Given
        when(taskSessionMapper.selectById("test-uuid")).thenReturn(mockSession);
        when(messageMapper.countBySessionId("test-uuid")).thenReturn(5L);
        when(documentMapper.countBySessionId("test-uuid")).thenReturn(2L);

        // When
        TaskSessionDTO result = taskSessionService.getSessionById("test-uuid");

        // Then
        assertNotNull(result);
        assertEquals("test-uuid", result.getId());
        assertEquals("测试会话", result.getTitle());
        assertEquals(5L, result.getMessageCount());
        assertEquals(2L, result.getDocumentCount());
    }

    @Test
    void getAllSessions_ShouldReturnList() {
        // Given
        List<TaskSession> sessions = Arrays.asList(mockSession);
        when(taskSessionMapper.selectAll()).thenReturn(sessions);
        when(messageMapper.countBySessionId(any())).thenReturn(0L);
        when(documentMapper.countBySessionId(any())).thenReturn(0L);

        // When
        List<TaskSessionDTO> result = taskSessionService.getAllSessions();

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("测试会话", result.get(0).getTitle());
    }

    @Test
    void updateSession_ShouldReturnUpdatedDTO() {
        // Given
        UpdateTaskSessionRequest request = UpdateTaskSessionRequest.builder()
                .title("更新后的标题")
                .status("completed")
                .build();

        when(taskSessionMapper.selectById("test-uuid")).thenReturn(mockSession);
        when(taskSessionMapper.updateById(any(TaskSession.class))).thenReturn(1);
        when(messageMapper.countBySessionId(any())).thenReturn(0L);
        when(documentMapper.countBySessionId(any())).thenReturn(0L);

        // When
        TaskSessionDTO result = taskSessionService.updateSession("test-uuid", request);

        // Then
        assertNotNull(result);
        verify(taskSessionMapper, times(1)).updateById(any(TaskSession.class));
    }

    @Test
    void deleteSession_ShouldCallMapper() {
        // Given
        when(taskSessionMapper.selectById("test-uuid")).thenReturn(mockSession);
        when(taskSessionMapper.deleteById("test-uuid")).thenReturn(1);

        // When
        taskSessionService.deleteSession("test-uuid");

        // Then
        verify(taskSessionMapper, times(1)).deleteById("test-uuid");
    }

    @Test
    void updateSessionStatus_ShouldReturnUpdatedDTO() {
        // Given
        when(taskSessionMapper.selectById("test-uuid")).thenReturn(mockSession);
        when(taskSessionMapper.updateById(any(TaskSession.class))).thenReturn(1);
        when(messageMapper.countBySessionId(any())).thenReturn(0L);
        when(documentMapper.countBySessionId(any())).thenReturn(0L);

        // When
        TaskSessionDTO result = taskSessionService.updateSessionStatus("test-uuid", "completed");

        // Then
        assertNotNull(result);
        verify(taskSessionMapper, times(1)).updateById(any(TaskSession.class));
    }
}
