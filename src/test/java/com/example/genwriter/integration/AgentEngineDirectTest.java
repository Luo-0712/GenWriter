package com.example.genwriter.integration;

import com.example.genwriter.agent.*;
import com.example.genwriter.agent.writerflow.KnowledgeAgent;
import com.example.genwriter.agent.writerflow.PolishAgent;
import com.example.genwriter.agent.writerflow.WritingAgent;
import com.example.genwriter.config.RealEnvironmentTestConfig;
import com.example.genwriter.event.ChatEvent;
import com.example.genwriter.service.SseService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ContextConfiguration(classes = RealEnvironmentTestConfig.class)
@TestPropertySource(locations = "classpath:application-real.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentEngineDirectTest {

    @Autowired
    private WritingAgent writingAgent;

    @Autowired
    private PolishAgent polishAgent;

    @Autowired
    private KnowledgeAgent knowledgeAgent;

    @Autowired
    private SseService sseService;

    @Autowired
    private AgentEngineFactory agentEngineFactory;

    @Test
    @Order(1)
    @DisplayName("直接测试 WritingAgent 执行")
    void testWritingAgentDirectly() {
        String input = "请写一段关于秋天的短文";
        String sessionId = "direct-test-" + System.currentTimeMillis();

        System.out.println("=== 直接测试 WritingAgent ===");
        System.out.println("输入: " + input);
        System.out.println("会话 ID: " + sessionId);

        try {
            String result = writingAgent.execute(input, null, sessionId);

            System.out.println("WritingAgent 执行完成");
            System.out.println("结果长度: " + (result != null ? result.length() : 0));
            System.out.println("结果预览: " + (result != null ? result.substring(0, Math.min(100, result.length())) : "null"));

            Assertions.assertNotNull(result);
            Assertions.assertFalse(result.isEmpty());
        } catch (Exception e) {
            System.out.println("WritingAgent 执行异常: " + e.getMessage());
        }
    }

    @Test
    @Order(2)
    @DisplayName("直接测试 PolishAgent 执行")
    void testPolishAgentDirectly() {
        String input = "润色：今天天气很好，我去公园玩";
        String sessionId = "polish-direct-test-" + System.currentTimeMillis();

        System.out.println("=== 直接测试 PolishAgent ===");
        System.out.println("输入: " + input);

        try {
            String result = polishAgent.execute(input, null, sessionId);

            System.out.println("PolishAgent 执行完成");
            System.out.println("结果长度: " + (result != null ? result.length() : 0));

            Assertions.assertNotNull(result);
        } catch (Exception e) {
            System.out.println("PolishAgent 执行异常: " + e.getMessage());
        }
    }

    @Test
    @Order(3)
    @DisplayName("直接测试 AgentEngine 调度")
    void testAgentEngineScheduling() {
        String sessionId = "engine-test-" + System.currentTimeMillis();
        String documentId = "doc-" + sessionId;

        System.out.println("=== 测试 AgentEngine 调度 ===");
        System.out.println("会话 ID: " + sessionId);

        try {
            AgentEngine engine = agentEngineFactory.create(sessionId, documentId);

            System.out.println("AgentEngine 创建成功");
            System.out.println("准备执行 CREATE 类型请求");

            engine.run("请写一篇关于冬天的诗", ChatEvent.WritingType.CREATE);

            System.out.println("AgentEngine 执行完成");
        } catch (Exception e) {
            System.out.println("AgentEngine 执行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @Order(4)
    @DisplayName("测试 SSE 发布和接收")
    void testSSEPublishAndReceive() {
        String sessionId = "sse-test-" + System.currentTimeMillis();

        System.out.println("=== 测试 SSE 发布机制 ===");

        sseService.publish(sessionId, com.example.genwriter.message.SseMessage.builder()
                .type(com.example.genwriter.message.SseMessage.Type.AI_THINKING)
                .payload(com.example.genwriter.message.SseMessage.Payload.builder()
                        .statusText("测试消息")
                        .build())
                .build());

        System.out.println("SSE 消息发布成功");

        boolean exists = sseService.hasChannel(sessionId);
        System.out.println("频道存在: " + exists);

        long lastSeq = sseService.getLastSequenceId(sessionId);
        System.out.println("最新序列号: " + lastSeq);

        Assertions.assertTrue(exists);
        Assertions.assertTrue(lastSeq > 0);

        sseService.complete(sessionId);
        System.out.println("SSE 频道已标记完成");
    }
}
