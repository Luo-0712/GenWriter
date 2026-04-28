package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.ResearcherSkill;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.WebSearchTool;
import com.example.genwriter.config.ResearcherProperties;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ResearcherWorker 单元测试
 */
@ExtendWith(MockitoExtension.class)
class ResearcherWorkerTest {

    @Mock
    private ChatClientFactory chatClientFactory;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private ResearcherSkill skill;

    @Mock
    private WebSearchTool webSearchTool;

    @Mock
    private WorkerRegistry registry;

    @Mock
    private SseService sseService;

    @Mock
    private ResearcherProperties properties;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ResearcherWorker researcherWorker;

    @BeforeEach
    void setUp() {
        lenient().when(chatClientFactory.create(anyDouble())).thenReturn(chatClient);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(responseSpec);

        researcherWorker = new ResearcherWorker(
                chatClientFactory, skill, webSearchTool, registry, sseService, objectMapper, properties
        );
        researcherWorker.init();

        lenient().when(properties.getMaxSearchQueries()).thenReturn(3);
        lenient().when(properties.getMaxSearchResultsPerQuery()).thenReturn(3);
        lenient().when(properties.getMaxVerificationLoops()).thenReturn(1);
    }

    @Test
    void name_ShouldReturnResearcher() {
        assertEquals("researcher", researcherWorker.name());
    }

    @Test
    void description_ShouldMentionMultiStep() {
        assertTrue(researcherWorker.description().contains("多步骤"));
        assertTrue(researcherWorker.description().contains("搜索"));
    }

    @Test
    void execute_WithSimpleQuery_ShouldRunAllPhases() throws Exception {
        // Given
        when(skill.buildPlanningPrompt(any(), any())).thenReturn("plan prompt");
        when(skill.buildSynthesisPrompt(any(), any())).thenReturn("synthesis prompt");
        when(skill.buildVerificationPrompt(any(), any())).thenReturn("verify prompt");
        when(skill.systemPrompt()).thenReturn("system");

        when(responseSpec.content())
                .thenReturn("{\"queries\":[\"q1\"],\"reasoning\":\"r\"}")  // plan
                .thenReturn("{\"researchReport\":\"report\",\"researchSources\":[],\"keyFindings\":[]}")  // synthesize
                .thenReturn("{\"isComplete\":true,\"gaps\":\"\",\"reasoning\":\"ok\"}");  // verify

        when(webSearchTool.search(anyString(), anyInt())).thenReturn(java.util.Collections.emptyList());

        Map<String, Object> state = Map.of(
                "sessionId", "test-session",
                "userInput", "测试查询"
        );

        // When
        Map<String, Object> result = researcherWorker.execute(state);

        // Then
        assertNotNull(result);
        assertTrue(result.containsKey("researchReport"));
        assertTrue(result.containsKey("researchSources"));
        assertTrue(result.containsKey("keyFindings"));
        assertTrue(result.containsKey("context"));
        assertEquals("ResearcherWorker", result.get("currentNode"));

        verify(webSearchTool, atLeastOnce()).search(anyString(), anyInt());
        verify(sseService, atLeastOnce()).publish(anyString(), any());
    }
}
