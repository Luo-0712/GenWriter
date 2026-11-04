package com.example.genwriter.agent.chain;

import com.example.genwriter.message.AgentTraceEvent;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ThoughtChainPublisherTest {

    @Mock
    private SseService sseService;

    private List<SseMessage> captureMessages(String sessionId) {
        ArgumentCaptor<SseMessage> captor = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, org.mockito.Mockito.atLeastOnce()).publish(eq(sessionId), captor.capture());
        return captor.getAllValues();
    }

    private List<AgentTraceEvent> captureTraceEvents(String sessionId) {
        return captureMessages(sessionId).stream()
                .filter(m -> m.getType() == SseMessage.Type.AI_TRACE_EVENT)
                .map(m -> (AgentTraceEvent) m.getPayload().getData())
                .toList();
    }

    @Test
    void publishReasoningChunk_usesAiReasoningChunkType() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        publisher.publishReasoningChunk("session-1", "node-1", "思考");

        List<SseMessage> messages = captureMessages("session-1");
        SseMessage reasoningMsg = messages.stream()
                .filter(m -> m.getType() == SseMessage.Type.AI_REASONING_CHUNK)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) reasoningMsg.getPayload().getData();
        assertEquals("node-1", data.get("nodeId"));
        assertEquals("思考", data.get("reasoningChunk"));
        // 不应再用 AI_THINKING 承载 reasoning chunk
        assertFalse(messages.stream().anyMatch(m -> m.getType() == SseMessage.Type.AI_THINKING
                && m.getPayload().getData() != null));
    }

    @Test
    void drainTraceEvents_returnsAccumulatedEventsWithReasoningContent() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String nodeId = publisher.publishStart("session-1", "正文写作",
                ChainNode.Type.EXECUTION, null, Map.of("k", "v"));
        publisher.publishComplete("session-1", nodeId, Map.of("length", 100), "完整的思考过程");

        List<AgentTraceEvent> drained = publisher.drainTraceEvents("session-1");
        assertFalse(drained.isEmpty(), "应累积至少一条 trace 事件");
        // COMPLETED 事件回填了 reasoningContent
        AgentTraceEvent completed = drained.stream()
                .filter(e -> nodeId.equals(e.getSpanId())
                        && AgentTraceEvent.Phase.COMPLETED.name().equals(e.getPhase()))
                .findFirst().orElseThrow();
        assertEquals("完整的思考过程", completed.getReasoningContent());
    }

    @Test
    void clearTraceEvents_emptiesBuffer() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String nodeId = publisher.publishStart("session-1", "大纲生成",
                ChainNode.Type.THINKING, null, null);
        publisher.publishComplete("session-1", nodeId, Map.of(), "reason");

        publisher.clearTraceEvents("session-1");
        // clear 后再 drain 应为空
        List<AgentTraceEvent> drained = publisher.drainTraceEvents("session-1");
        assertTrue(drained.isEmpty());
    }

    @Test
    void drainTraceEvents_emptyForUnknownSession() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);
        List<AgentTraceEvent> drained = publisher.drainTraceEvents("unknown-session");
        assertTrue(drained.isEmpty());
    }

    @Test
    void publishReactDecisionTrace_pushesReasoningIntoDedicatedField() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String spanId = publisher.publishReactDecisionTrace(
                "session-1", "supervisor-node-1",
                "用户要写小说，先识别意图", "需先确定 writingType", "intent_recognition",
                ThoughtChainPublisher.DECISION_REACT);

        assertNotNull(spanId);
        List<AgentTraceEvent> traces = captureTraceEvents("session-1");
        AgentTraceEvent decision = traces.stream()
                .filter(e -> spanId.equals(e.getSpanId()))
                .findFirst().orElseThrow();
        assertEquals(AgentTraceEvent.Kind.LLM.name(), decision.getKind());
        assertEquals("ReAct 决策", decision.getName());
        assertEquals("supervisor-node-1", decision.getParentSpanId());
        assertEquals("intent_recognition", decision.getMetadata().get("action"));
        assertEquals(ThoughtChainPublisher.DECISION_REACT, decision.getMetadata().get("decisionType"));
        // thought/reasoning 迁移到 reasoningContent 专属字段（前端「模型思考过程」区块渲染），
        // metadata 中不再重复
        assertEquals("用户要写小说，先识别意图\n\n需先确定 writingType", decision.getReasoningContent());
        assertFalse(decision.getMetadata().containsKey("reasoning"));
        assertFalse(decision.getMetadata().containsKey("thought"));
    }

    @Test
    void publishReactDecisionTrace_reasoningNotTruncated() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);
        String longReasoning = "思".repeat(600);

        String spanId = publisher.publishReactDecisionTrace(
                "session-1", "supervisor-node-1", "决策", longReasoning, "draft",
                ThoughtChainPublisher.DECISION_REACT);

        AgentTraceEvent decision = captureTraceEvents("session-1").stream()
                .filter(e -> spanId.equals(e.getSpanId())).findFirst().orElseThrow();
        // reasoningContent = thought + "\n\n" + reasoning，reasoning 部分保持全文不被 240 字截断
        assertEquals("决策\n\n" + longReasoning, decision.getReasoningContent(),
                "思考全文不应被 240 字截断");
    }

    @Test
    void publishReasoningComplete_mergesReasoningIntoSpanForPersistence() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String nodeId = publisher.publishStart("session-1", "正文写作",
                ChainNode.Type.EXECUTION, null, null);
        // 模拟装饰器在流结束时补录思考全文（AI_REASONING_CHUNK 本身不落库），随后 Worker 正常完成
        publisher.publishReasoningComplete("session-1", nodeId, "流式思考全文");
        publisher.publishComplete("session-1", nodeId, Map.of("length", 10), null);

        List<AgentTraceEvent> drained = publisher.drainTraceEvents("session-1");
        long sameSpanCount = drained.stream().filter(e -> nodeId.equals(e.getSpanId())).count();
        assertEquals(1, sameSpanCount, "同 spanId 事件应合并为一条缓冲记录");
        AgentTraceEvent merged = drained.stream()
                .filter(e -> nodeId.equals(e.getSpanId())).findFirst().orElseThrow();
        assertEquals(AgentTraceEvent.Phase.COMPLETED.name(), merged.getPhase());
        assertEquals("流式思考全文", merged.getReasoningContent(),
                "补录的思考全文应保留到最终缓冲，保证会话刷新后可恢复");
    }

    @Test
    void publishReactDecisionTrace_marksGuardSpansAsSupervisorKind() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String spanId = publisher.publishReactDecisionTrace(
                "session-1", "supervisor-node-1",
                "正文已更新，先按新稿重新润色", null, "polish",
                ThoughtChainPublisher.DECISION_GUARD);

        AgentTraceEvent decision = captureTraceEvents("session-1").stream()
                .filter(e -> spanId.equals(e.getSpanId())).findFirst().orElseThrow();
        // 护栏步不是模型调用，归到 SUPERVISOR：不该出现在前端的「模型」分类与标签里
        assertEquals(AgentTraceEvent.Kind.SUPERVISOR.name(), decision.getKind());
        assertEquals(ThoughtChainPublisher.DECISION_GUARD, decision.getMetadata().get("decisionType"));
    }

    @Test
    void publishReactDecisionComplete_keepsDecisionSummaryInsteadOfFallback() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String spanId = publisher.publishReactDecisionTrace(
                "session-1", "supervisor-node-1",
                "大纲已生成，进入正文草稿", null, "draft", ThoughtChainPublisher.DECISION_GUARD);
        publisher.publishReactDecisionComplete("session-1", spanId, "大纲已生成，进入正文草稿");

        AgentTraceEvent merged = publisher.drainTraceEvents("session-1").stream()
                .filter(e -> spanId.equals(e.getSpanId()))
                .findFirst().orElseThrow();
        assertEquals(AgentTraceEvent.Phase.COMPLETED.name(), merged.getPhase());
        assertEquals("大纲已生成，进入正文草稿", merged.getSummary(),
                "COMPLETED 摘要应保留决策依据，而不是退化成兜底文案");
        assertEquals(ThoughtChainPublisher.DECISION_GUARD, merged.getMetadata().get("decisionType"));
    }

    @Test
    void buildSummary_usesOutputLengthAndReasoningInsteadOfFallback() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String llmSpanId = publisher.publishTraceStart("session-1", "模型撰写正文",
                AgentTraceEvent.Kind.LLM, "worker-node-1", null, null);
        publisher.publishTraceComplete("session-1", llmSpanId,
                Map.of("outputLength", 385, "reasoningCaptured", false));

        String planNodeId = publisher.publishStart("session-1", "任务规划",
                ChainNode.Type.PLANNING, null, null);
        publisher.publishComplete("session-1", planNodeId,
                Map.of("action", "FINISH", "reasoning", "ReAct 完成"));

        String reviewNodeId = publisher.publishStart("session-1", "内容评审",
                ChainNode.Type.THINKING, null, null);
        publisher.publishComplete("session-1", reviewNodeId,
                Map.of("score", 7, "verdict", "REVISE_DRAFT", "feedback", "篇幅超标"));

        List<AgentTraceEvent> drained = publisher.drainTraceEvents("session-1");
        assertEquals("输出 385 字符", summaryOf(drained, llmSpanId));
        assertEquals("ReAct 完成", summaryOf(drained, planNodeId));
        assertEquals("评审 REVISE_DRAFT", summaryOf(drained, reviewNodeId));
    }

    private String summaryOf(List<AgentTraceEvent> events, String spanId) {
        return events.stream()
                .filter(e -> spanId.equals(e.getSpanId()))
                .findFirst().orElseThrow()
                .getSummary();
    }

    @Test
    void traceEvents_sameSpanIdMergedAcrossPhases() {
        // 同 spanId 的 STARTED + COMPLETED 事件应在缓冲中合并为一条
        // （COMPLETED 回填的 reasoningContent 体现在最终 drained 的事件上）
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String nodeId = publisher.publishStart("session-1", "润色",
                ChainNode.Type.EXECUTION, null, null);
        publisher.publishComplete("session-1", nodeId, Map.of("length", 50), "润色思考");

        List<AgentTraceEvent> drained = publisher.drainTraceEvents("session-1");
        // 缓冲中同 spanId 合并为一条，phase 取最新（COMPLETED），reasoningContent 已回填
        long sameSpanCount = drained.stream().filter(e -> nodeId.equals(e.getSpanId())).count();
        assertEquals(1, sameSpanCount, "同 spanId 事件应合并为一条缓冲记录");
        AgentTraceEvent merged = drained.stream()
                .filter(e -> nodeId.equals(e.getSpanId())).findFirst().orElseThrow();
        assertEquals(AgentTraceEvent.Phase.COMPLETED.name(), merged.getPhase());
        assertEquals("润色思考", merged.getReasoningContent());
    }
}
