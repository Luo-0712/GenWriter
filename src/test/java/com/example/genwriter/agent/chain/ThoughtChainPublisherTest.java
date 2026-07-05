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
    void publishReactDecisionTrace_pushesLlmSpanWithThoughtAndReasoning() {
        ThoughtChainPublisher publisher = new ThoughtChainPublisher(sseService);

        String spanId = publisher.publishReactDecisionTrace(
                "session-1", "supervisor-node-1",
                "用户要写小说，先识别意图", "需先确定 writingType", "intent_recognition");

        assertNotNull(spanId);
        List<AgentTraceEvent> traces = captureTraceEvents("session-1");
        AgentTraceEvent decision = traces.stream()
                .filter(e -> spanId.equals(e.getSpanId()))
                .findFirst().orElseThrow();
        assertEquals(AgentTraceEvent.Kind.LLM.name(), decision.getKind());
        assertEquals("ReAct 决策", decision.getName());
        assertEquals("supervisor-node-1", decision.getParentSpanId());
        assertEquals("intent_recognition", decision.getMetadata().get("action"));
        assertEquals("需先确定 writingType", decision.getMetadata().get("reasoning"));
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
