package com.example.genwriter.integration;

import com.example.genwriter.config.RealEnvironmentTestConfig;
import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.model.dto.request.CreateMessageRequest;
import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
import com.example.genwriter.model.dto.response.MessageDTO;
import com.example.genwriter.model.dto.response.TaskSessionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = RealEnvironmentTestConfig.class)
@TestPropertySource(locations = "classpath:application-real.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String sessionId;

    @Test
    @Order(1)
    @DisplayName("创建写作会话")
    void createWritingSession() throws Exception {
        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("测试写作会话")
                .type("writing")
                .build();

        MvcResult result = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.title").value("测试写作会话"))
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        sessionId = objectMapper.readTree(responseJson).get("data").get("id").asText();

        Assertions.assertNotNull(sessionId);
        Assertions.assertFalse(sessionId.isEmpty());
        System.out.println("创建的会话 ID: " + sessionId);
    }

    @Test
    @Order(2)
    @DisplayName("查询会话信息")
    void getSessionInfo() throws Exception {
        mockMvc.perform(get("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.id").value(sessionId));
    }

    @Test
    @Order(3)
    @DisplayName("SSE订阅连接")
    void subscribeToSSE() throws Exception {
        AtomicReference<MvcResult> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        mockMvc.perform(get("/sse/subscribe/{sessionId}", sessionId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        System.out.println("SSE 订阅成功，会话: " + sessionId);
    }

    @Test
    @Order(4)
    @DisplayName("发送新建文档请求")
    void sendCreateChatRequest() throws Exception {
        String userInput = "请帮我写一篇关于春天的短文，100字左右";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionId)
                        .param("type", ChatEvent.WritingType.CREATE.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("发送聊天请求，类型: CREATE");
    }

    @Test
    @Order(5)
    @DisplayName("获取历史消息")
    void getHistoryMessages() throws Exception {
        mockMvc.perform(get("/api/messages/session/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("获取历史消息成功");
    }

    @Test
    @Order(6)
    @DisplayName("发送润色请求")
    void sendPolishChatRequest() throws Exception {
        String polishSessionId = UUID.randomUUID().toString();

        CreateTaskSessionRequest sessionRequest = CreateTaskSessionRequest.builder()
                .title("润色测试会话")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionRequest)))
                .andExpect(status().isOk());

        String userInput = "今天天气很好，我去公园散步，看到很多花开了";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", polishSessionId)
                        .param("type", ChatEvent.WritingType.POLISH.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("发送润色请求成功");
    }

    @Test
    @Order(7)
    @DisplayName("发送续写请求")
    void sendContinueChatRequest() throws Exception {
        String continueSessionId = UUID.randomUUID().toString();

        CreateTaskSessionRequest sessionRequest = CreateTaskSessionRequest.builder()
                .title("续写测试会话")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionRequest)))
                .andExpect(status().isOk());

        String userInput = "在上一段的基础上，继续描写春天的景色";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", continueSessionId)
                        .param("type", ChatEvent.WritingType.CONTINUE.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("发送续写请求成功");
    }

    @Test
    @Order(8)
    @DisplayName("查询最近消息")
    void getRecentMessages() throws Exception {
        mockMvc.perform(get("/api/messages/session/{sessionId}/recent", sessionId)
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray());

        System.out.println("查询最近消息成功");
    }

    @Test
    @Order(9)
    @DisplayName("获取会话状态")
    void getSessionStatus() throws Exception {
        mockMvc.perform(get("/sse/status/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").isBoolean());

        System.out.println("获取会话状态成功");
    }

    @Test
    @Order(10)
    @DisplayName("取消SSE订阅")
    void unsubscribeFromSSE() throws Exception {
        mockMvc.perform(delete("/sse/unsubscribe/{sessionId}", sessionId))
                .andExpect(status().isOk());

        System.out.println("取消SSE订阅成功");
    }

    @Test
    @Order(11)
    @DisplayName("删除会话")
    void deleteSession() throws Exception {
        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("删除会话成功");
    }
}
