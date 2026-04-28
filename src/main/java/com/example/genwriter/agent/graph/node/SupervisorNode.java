package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.supervisor.SupervisorDecision;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.agent.supervisor.SupervisorSystemPromptProvider;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorNode implements NodeAction {

    private final ChatClientFactory chatClientFactory;
    private final WorkerRegistry workerRegistry;
    private final SupervisorSystemPromptProvider promptProvider;
    private final SupervisorModeProperties properties;
    private final SseService sseService;
    private final ObjectMapper objectMapper;

    private ChatClient chatClient;
    private String systemPrompt;

    @PostConstruct
    void init() {
        this.chatClient = chatClientFactory.create(properties.getTemperature());
        this.systemPrompt = promptProvider.buildSystemPrompt();
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");

        Map<String, Object> accumulated = new HashMap<>();
        accumulated.put("sessionId", sessionId);
        accumulated.put("documentId", state.value("documentId", String.class).orElse(""));
        accumulated.put("userInput", state.value("userInput", String.class).orElse(""));
        accumulated.put("kbId", state.value("kbId", String.class).orElse(""));
        accumulated.put("writingType", state.value("writingType", String.class).orElse("CREATE"));
        accumulated.put("context", state.value("context", String.class).orElse(""));

        StringBuilder historyLog = new StringBuilder();

        publishStatus(sessionId, "【监督者】分析任务，开始调度 Worker...");

        for (int iteration = 1; iteration <= properties.getMaxIterations(); iteration++) {
            log.info("Supervisor 迭代 {}/{}: sessionId={}", iteration, properties.getMaxIterations(), sessionId);

            String userPrompt = buildIterationPrompt(accumulated, historyLog.toString());

            String response;
            try {
                response = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
            } catch (Exception e) {
                log.error("Supervisor LLM 调用失败: iteration={}", iteration, e);
                return finishWithDirectAnswer(accumulated);
            }

            SupervisorDecision decision;
            try {
                decision = parseDecision(response);
            } catch (Exception e) {
                log.warn("Supervisor 决策解析失败: iteration={}, response={}", iteration, response, e);
                return finishWithDirectAnswer(accumulated);
            }

            if (SupervisorDecision.FINISH.equals(decision.action())) {
                log.info("Supervisor 决定完成: reasoning={}", decision.reasoning());
                publishStatus(sessionId, "【监督者】任务完成 — " + decision.reasoning());

                String finalOutput = decision.finalOutput();
                if (finalOutput == null || finalOutput.isBlank()) {
                    finalOutput = (String) accumulated.getOrDefault("polishedContent",
                            accumulated.getOrDefault("draft",
                                    accumulated.getOrDefault("finalOutput", "")));
                }
                accumulated.put("finalOutput", finalOutput);
                accumulated.put("supervisorReasoning", decision.reasoning());
                accumulated.put("supervisorIterations", iteration);
                accumulated.put("currentNode", "SupervisorNode");
                return accumulated;
            }

            if (SupervisorDecision.CALL_WORKER.equals(decision.action())) {
                String workerName = decision.workerName();
                log.info("Supervisor 调用 Worker: name={}, reasoning={}", workerName, decision.reasoning());
                publishStatus(sessionId, "【监督者】调用 " + workerName + " — " + decision.reasoning());

                WorkerAgent worker = workerRegistry.get(workerName);
                if (worker == null) {
                    log.warn("Worker 不存在: name={}, 降级到 direct_answer", workerName);
                    // 重新注册 prompt（Worker 可能因为懒加载未注册）
                    worker = workerRegistry.get(properties.getFallbackWorker());
                }

                try {
                    Map<String, Object> result = worker.execute(new HashMap<>(accumulated));
                    accumulated.putAll(result);
                    historyLog.append("[")
                            .append(iteration)
                            .append("] CALL ")
                            .append(workerName)
                            .append(" -> ")
                            .append(result.keySet())
                            .append("\n");
                    publishStatus(sessionId, "【监督者】" + workerName + " 完成");
                } catch (Exception e) {
                    log.error("Worker 执行失败: name={}, error={}", workerName, e.getMessage(), e);
                    historyLog.append("[").append(iteration).append("] CALL ").append(workerName)
                            .append(" -> ERROR: ").append(e.getMessage()).append("\n");
                    publishStatus(sessionId, "【监督者】" + workerName + " 执行超时，跳过");

                    if (iteration >= properties.getMaxIterations()) {
                        return finishWithDirectAnswer(accumulated);
                    }
                }
            } else {
                log.warn("未知 action: {}, 降级 FINISH", decision.action());
                return finishWithDirectAnswer(accumulated);
            }
        }

        log.warn("Supervisor 达到最大迭代次数({})，强制结束", properties.getMaxIterations());
        publishStatus(sessionId, "【监督者】任务结束（超过最大轮次）");
        return finishWithDirectAnswer(accumulated);
    }

    private String buildIterationPrompt(Map<String, Object> state, String historyLog) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- userInput: ").append(truncate((String) state.getOrDefault("userInput", ""), 200)).append("\n");
        sb.append("- kbId: ").append(state.getOrDefault("kbId", "")).append("\n");
        sb.append("- writingType: ").append(state.getOrDefault("writingType", "CREATE")).append("\n");

        if (state.containsKey("intent")) {
            sb.append("- intent: ").append(state.get("intent")).append("\n");
        }
        if (state.containsKey("outline")) {
            String outline = (String) state.get("outline");
            sb.append("- outline: ").append(outline != null ? outline.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("draft")) {
            String draft = (String) state.get("draft");
            sb.append("- draft: ").append(draft != null ? draft.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("polishedContent")) {
            String pc = (String) state.get("polishedContent");
            sb.append("- polishedContent: ").append(pc != null ? pc.length() + " chars" : "null").append("\n");
        }
        if (state.containsKey("reviewResult")) {
            sb.append("- reviewResult: ").append(state.get("reviewResult")).append("\n");
        }
        if (state.containsKey("reviewFeedback")) {
            sb.append("- reviewFeedback: ").append(truncate((String) state.getOrDefault("reviewFeedback", ""), 200)).append("\n");
        }
        if (state.containsKey("reviewCount")) {
            sb.append("- reviewCount: ").append(state.get("reviewCount")).append("\n");
        }
        if (state.containsKey("context")) {
            String ctx = (String) state.get("context");
            sb.append("- context: ").append(ctx != null && !ctx.isBlank() ? ctx.length() + " chars" : "empty").append("\n");
        }

        sb.append("\n## 调度历史\n");
        if (historyLog.isBlank()) {
            sb.append("(首次调度，无历史)\n");
        } else {
            sb.append(historyLog);
        }

        sb.append("\n请根据当前状态和历史，决定下一步动作（CALL_WORKER 或 FINISH）。");
        sb.append("\n当前是调度轮次，请务必按 JSON 格式输出决策。");
        return sb.toString();
    }

    private SupervisorDecision parseDecision(String response) throws Exception {
        String json = stripMarkdownCodeBlock(response);
        return objectMapper.readValue(json, SupervisorDecision.class);
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private Map<String, Object> finishWithDirectAnswer(Map<String, Object> state) {
        WorkerAgent directAnswer = workerRegistry.get(properties.getFallbackWorker());
        if (directAnswer != null) {
            try {
                Map<String, Object> result = directAnswer.execute(new HashMap<>(state));
                result.put("currentNode", "SupervisorNode");
                return result;
            } catch (Exception e) {
                log.warn("direct_answer Worker 执行也失败", e);
            }
        }
        return Map.of(
                "finalOutput", state.getOrDefault("finalOutput", ""),
                "currentNode", "SupervisorNode"
        );
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE status publish failed: {}", e.getMessage());
        }
    }
}
