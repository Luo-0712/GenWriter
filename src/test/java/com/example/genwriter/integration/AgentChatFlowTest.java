package com.example.genwriter.integration;

import com.example.genwriter.config.RealEnvironmentTestConfig;
import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.model.dto.request.CreateTaskSessionRequest;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = RealEnvironmentTestConfig.class)
@TestPropertySource(locations = "classpath:application-real.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentChatFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Order(1)
    @DisplayName("完整流程：创建会话 -> 发送消息 -> 接收SSE推送 -> 完成")
    void testFullChatFlow() throws Exception {
        String sessionId = UUID.randomUUID().toString();
        System.out.println("=== 开始完整测试流程 ===");
        System.out.println("会话 ID: " + sessionId);

        CreateTaskSessionRequest sessionRequest = CreateTaskSessionRequest.builder()
                .title("完整流程测试")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sessionRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("1. 创建会话成功");

        mockMvc.perform(get("/sse/subscribe/{sessionId}", sessionId)
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        System.out.println("2. SSE 订阅成功");

        String userInput = "请帮我写一篇关于人工智能的短文";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionId)
                        .param("type", ChatEvent.WritingType.CREATE.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("3. 发送聊天请求成功");

        Thread.sleep(2000);

        mockMvc.perform(get("/sse/status/{sessionId}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").isBoolean());

        System.out.println("4. 检查会话状态成功");

        mockMvc.perform(get("/api/messages/session/{sessionId}/recent", sessionId)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray());

        System.out.println("5. 获取消息记录成功");

        mockMvc.perform(delete("/sse/unsubscribe/{sessionId}", sessionId))
                .andExpect(status().isOk());

        System.out.println("6. 取消订阅成功");

        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("7. 删除会话成功");
        System.out.println("=== 完整测试流程结束 ===");
    }

    @Test
    @Order(2)
    @DisplayName("测试新建文档 (CREATE)")
    void testCreateDocument() throws Exception {
        String sessionId = "test-create-" + UUID.randomUUID().toString().substring(0, 8);

        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("新建文档测试")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        String userInput = "写一篇关于夏天的诗歌";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionId)
                        .param("type", ChatEvent.WritingType.CREATE.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("新建文档测试完成");

        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    @DisplayName("测试润色文本 (POLISH)")
    void testPolishText() throws Exception {
        String sessionId = "test-polish-" + UUID.randomUUID().toString().substring(0, 8);

        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("润色测试")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        String userInput = "润色以下文本：今天天气很好，我去公园玩";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionId)
                        .param("type", ChatEvent.WritingType.POLISH.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("润色文本测试完成");

        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk());
    }

    @Test
    @Order(4)
    @DisplayName("测试文档续写 (CONTINUE)")
    void testContinueDocument() throws Exception {
        String sessionId = "test-continue-" + UUID.randomUUID().toString().substring(0, 8);

        CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                .title("续写测试")
                .type("writing")
                .build();

        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        String userInput = "继续写下一段内容";

        mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionId)
                        .param("type", ChatEvent.WritingType.CONTINUE.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"" + userInput + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        System.out.println("文档续写测试完成");

        mockMvc.perform(delete("/api/sessions/" + sessionId))
                .andExpect(status().isOk());
    }

    @Test
    @Order(5)
    @DisplayName("测试并发多个会话")
    void testConcurrentSessions() throws Exception {
        int sessionCount = 3;
        String[] sessionIds = new String[sessionCount];

        for (int i = 0; i < sessionCount; i++) {
            sessionIds[i] = "concurrent-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);

            CreateTaskSessionRequest request = CreateTaskSessionRequest.builder()
                    .title("并发测试会话-" + i)
                    .type("writing")
                    .build();

            mockMvc.perform(post("/api/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());

            String userInput = "写一段关于春天的描述";

            mockMvc.perform(post("/api/messages/{sessionId}/chat", sessionIds[i])
                            .param("type", ChatEvent.WritingType.CREATE.name())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("\"" + userInput + "\""))
                    .andExpect(status().isOk());

            System.out.println("并发会话 " + i + " 创建并发送请求成功");
        }

        Thread.sleep(1000);

        for (String sessionId : sessionIds) {
            mockMvc.perform(delete("/api/sessions/" + sessionId))
                    .andExpect(status().isOk());
        }

        System.out.println("并发测试完成");
    }
}
