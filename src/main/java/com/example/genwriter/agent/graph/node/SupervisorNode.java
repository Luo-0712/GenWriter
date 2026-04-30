package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.supervisor.ExecutionPlan;
import com.example.genwriter.agent.supervisor.SupervisorDecision;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.agent.supervisor.SupervisorSystemPromptProvider;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;

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
    private final LongTermMemoryService memoryService;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;

    private ChatClient chatClient;
    private String systemPrompt;

    private static final Map<String, String> WORKER_DISPLAY_NAMES = Map.of(
            "intent_recognition", "意图识别",
            "outline", "大纲生成",
            "draft", "正文写作",
            "polish", "润色优化",
            "review", "内容评审",
            "researcher", "网络调研",
            "direct_answer", "直接回答"
    );

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

        String supervisorNodeId = chainPublisher.publishStart(sessionId, "任务规划",
                ChainNode.Type.PLANNING, null,
                Map.of("userInput", truncate((String) accumulated.getOrDefault("userInput", ""), 200)));

        ExecutionPlan plan = generatePlan(state, accumulated);
        if (plan == null) {
            if (accumulated.containsKey("finalOutput") && !((String) accumulated.get("finalOutput")).isBlank()) {
                chainPublisher.publishComplete(sessionId, supervisorNodeId,
                        Map.of("action", "FINISH", "reasoning", "直接生成回答"));
            } else {
                chainPublisher.publishComplete(sessionId, supervisorNodeId,
                        Map.of("action", "FALLBACK", "reasoning", "规划失败，降级为直接回答"));
            }
            return finishWithDirectAnswer(accumulated);
        }

        List<String> steps = plan.steps();
        chainPublisher.publishComplete(sessionId, supervisorNodeId,
                Map.of("steps", steps, "reasoning", plan.reasoning()));

        publishStatus(sessionId, "【规划】执行计划: " + String.join(" → ", steps));
        log.info("[SupervisorNode] 执行计划: steps={}, reasoning={}", steps, plan.reasoning());

        int planIndex = 0;
        int replanCount = 0;
        int stepCounter = 0;
        while (planIndex < steps.size()) {
            String workerName = steps.get(planIndex);

            if (needsReplan(accumulated, workerName)) {
                if (replanCount >= properties.getMaxReplanCount()) {
                    log.warn("[SupervisorNode] 重规划次数已达上限({})，强制结束", properties.getMaxReplanCount());
                    chainPublisher.publishDirect(sessionId, "replan-" + replanCount, "重规划",
                            ChainNode.Type.PLANNING, supervisorNodeId,
                            ChainNode.Status.ERROR, null,
                            Map.of("reason", "重规划次数已达上限"), null);
                    return finishWithDirectAnswer(accumulated);
                }

                String replanNodeId = chainPublisher.publishStart(sessionId, "重规划 #" + (replanCount + 1),
                        ChainNode.Type.PLANNING, supervisorNodeId,
                        Map.of("reason", accumulated.get("reviewResult")));

                ExecutionPlan newPlan = replan(state, accumulated);
                if (newPlan != null) {
                    steps = new ArrayList<>(newPlan.steps());
                    planIndex = Math.min(newPlan.restartFrom(), newPlan.steps().size() - 1);
                    accumulated.remove("reviewResult");
                    replanCount++;
                    chainPublisher.publishComplete(sessionId, replanNodeId,
                            Map.of("newSteps", steps, "restartFrom", planIndex));
                    publishStatus(sessionId, "【重规划】调整为: " + String.join(" → ", steps));
                    log.info("[SupervisorNode] 重规划: steps={}, restartFrom={}, replanCount={}", steps, planIndex, replanCount);
                    continue;
                }
                chainPublisher.publishComplete(sessionId, replanNodeId,
                        Map.of("result", "重规划失败，继续执行"));
                accumulated.remove("reviewResult");
            }

            WorkerAgent worker = workerRegistry.get(workerName);
            if (worker == null) {
                log.warn("[SupervisorNode] Worker 不存在: name={}, 跳过", workerName);
                planIndex++;
                continue;
            }

            String displayName = WORKER_DISPLAY_NAMES.getOrDefault(workerName, workerName);
            String workerNodeId = chainPublisher.publishStart(sessionId, displayName,
                    ChainNode.Type.EXECUTION, supervisorNodeId,
                    Map.of("worker", workerName, "step", stepCounter + 1));

            publishStatus(sessionId, "正在调用 " + displayName + "...");
            log.info("[SupervisorNode] 执行 Worker: name={}, step={}/{}", workerName, planIndex + 1, steps.size());

            try {
                Map<String, Object> result = worker.execute(new HashMap<>(accumulated));
                accumulated.putAll(result);

                Object outputSummary = buildWorkerOutputSummary(workerName, result);
                chainPublisher.publishComplete(sessionId, workerNodeId, outputSummary);
                planIndex++;
                stepCounter++;
            } catch (Exception e) {
                log.error("[SupervisorNode] Worker 执行失败: name={}, error={}", workerName, e.getMessage(), e);
                chainPublisher.publishError(sessionId, workerNodeId, e.getMessage());
                publishStatus(sessionId, "步骤 " + displayName + " 执行异常，跳过继续...");
                planIndex++;
                stepCounter++;
            }
        }

        String finalOutput = extractFinalOutput(accumulated);
        accumulated.put("finalOutput", finalOutput);
        accumulated.put("currentNode", "SupervisorNode");

        publishStatus(sessionId, "任务完成");
        return accumulated;
    }

    private Object buildWorkerOutputSummary(String workerName, Map<String, Object> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("worker", workerName);
        if (result.containsKey("intent")) {
            summary.put("intent", result.get("intent"));
        }
        if (result.containsKey("writingType")) {
            summary.put("writingType", result.get("writingType"));
        }
        if (result.containsKey("outline")) {
            String outline = (String) result.get("outline");
            summary.put("outlineLength", outline != null ? outline.length() : 0);
        }
        if (result.containsKey("draft")) {
            String draft = (String) result.get("draft");
            summary.put("draftLength", draft != null ? draft.length() : 0);
        }
        if (result.containsKey("polishedContent")) {
            String pc = (String) result.get("polishedContent");
            summary.put("polishedLength", pc != null ? pc.length() : 0);
        }
        if (result.containsKey("reviewResult")) {
            summary.put("reviewResult", result.get("reviewResult"));
        }
        if (result.containsKey("reviewCount")) {
            summary.put("reviewCount", result.get("reviewCount"));
        }
        if (result.containsKey("searchRounds")) {
            summary.put("searchRounds", result.get("searchRounds"));
        }
        if (result.containsKey("finalOutput")) {
            String fo = (String) result.get("finalOutput");
            summary.put("outputLength", fo != null ? fo.length() : 0);
        }
        return summary;
    }

    private ExecutionPlan generatePlan(OverAllState state, Map<String, Object> accumulated) {
        String userPrompt = buildPlanPrompt(accumulated);

        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[SupervisorNode] LLM 调用失败", e);
            return null;
        }

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            String action = root.path("action").asText("");

            if (SupervisorDecision.FINISH.equals(action)) {
                String finalOutput = root.path("finalOutput").asText("");
                String reasoning = root.path("reasoning").asText("");
                log.info("[SupervisorNode] 直接完成: reasoning={}", reasoning);
                accumulated.put("finalOutput", finalOutput);
                accumulated.put("currentNode", "SupervisorNode");
                return null;
            }

            if (SupervisorDecision.PLAN.equals(action)) {
                List<String> steps = new ArrayList<>();
                JsonNode stepsNode = root.path("steps");
                if (stepsNode.isArray()) {
                    for (JsonNode s : stepsNode) {
                        if (s.isTextual()) steps.add(s.asText());
                    }
                }
                String reasoning = root.path("reasoning").asText("");

                if (steps.isEmpty()) {
                    log.warn("[SupervisorNode] 计划步骤为空，降级");
                    return null;
                }

                if (steps.size() > properties.getMaxIterations()) {
                    steps = steps.subList(0, properties.getMaxIterations());
                }

                return ExecutionPlan.of(steps, reasoning);
            }

            log.warn("[SupervisorNode] 未知 action: {}, 降级", action);
            return null;
        } catch (Exception e) {
            log.warn("[SupervisorNode] 计划解析失败: response={}", response, e);
            return null;
        }
    }

    private ExecutionPlan replan(OverAllState state, Map<String, Object> accumulated) {
        String userPrompt = buildReplanPrompt(accumulated);

        String response;
        try {
            response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[SupervisorNode] 重规划 LLM 调用失败", e);
            return null;
        }

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            String action = root.path("action").asText("");

            if (SupervisorDecision.FINISH.equals(action)) {
                String finalOutput = root.path("finalOutput").asText("");
                accumulated.put("finalOutput", finalOutput);
                return null;
            }

            if (SupervisorDecision.PLAN.equals(action)) {
                List<String> steps = new ArrayList<>();
                JsonNode stepsNode = root.path("steps");
                if (stepsNode.isArray()) {
                    for (JsonNode s : stepsNode) {
                        if (s.isTextual()) steps.add(s.asText());
                    }
                }
                String reasoning = root.path("reasoning").asText("");
                int restartFrom = root.path("restartFrom").asInt(0);

                if (steps.isEmpty()) return null;
                return new ExecutionPlan(steps, reasoning, restartFrom);
            }

            return null;
        } catch (Exception e) {
            log.warn("[SupervisorNode] 重规划解析失败: response={}", response, e);
            return null;
        }
    }

    private boolean needsReplan(Map<String, Object> accumulated, String nextWorker) {
        if (!"review".equals(nextWorker) && !"draft".equals(nextWorker) && !"polish".equals(nextWorker)) {
            return false;
        }
        String reviewResult = (String) accumulated.get("reviewResult");
        if (reviewResult == null) return false;
        return "REVISE_DRAFT".equals(reviewResult) || "REVISE_POLISH".equals(reviewResult);
    }

    private String buildPlanPrompt(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- userInput: ").append(truncate((String) state.getOrDefault("userInput", ""), 500)).append("\n");
        sb.append("- kbId: ").append(state.getOrDefault("kbId", "")).append("\n");
        sb.append("- writingType: ").append(state.getOrDefault("writingType", "CREATE")).append("\n");

        appendStateSummary(sb, state);
        appendMemoryContext(sb, state);

        sb.append("\n请根据当前状态制定完整的执行计划，或直接 FINISH。");
        return sb.toString();
    }

    private String buildReplanPrompt(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态（需要重规划）\n");
        sb.append("- userInput: ").append(truncate((String) state.getOrDefault("userInput", ""), 500)).append("\n");
        sb.append("- kbId: ").append(state.getOrDefault("kbId", "")).append("\n");

        appendStateSummary(sb, state);
        appendMemoryContext(sb, state);

        if (state.containsKey("reviewResult")) {
            sb.append("- reviewResult: ").append(state.get("reviewResult")).append("\n");
        }
        if (state.containsKey("reviewFeedback")) {
            sb.append("- reviewFeedback: ").append(truncate((String) state.getOrDefault("reviewFeedback", ""), 300)).append("\n");
        }
        if (state.containsKey("reviewCount")) {
            sb.append("- reviewCount: ").append(state.get("reviewCount")).append("\n");
        }

        sb.append("\n评审未通过，请重新制定执行计划。");
        sb.append("\n如果是 REVISE_DRAFT，从 draft 重新开始；如果是 REVISE_POLISH，从 polish 重新开始。");
        sb.append("\n请在 steps 中包含从重开始点到 review 的完整步骤，并设置 restartFrom 为重开始的索引。");
        return sb.toString();
    }

    private void appendStateSummary(StringBuilder sb, Map<String, Object> state) {
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
        if (state.containsKey("context")) {
            String ctx = (String) state.get("context");
            sb.append("- context: ").append(ctx != null && !ctx.isBlank() ? ctx.length() + " chars" : "empty").append("\n");
        }
        if (state.containsKey("researchReport")) {
            String report = (String) state.get("researchReport");
            sb.append("- researchReport: ").append(report != null ? report.length() + " chars" : "null").append("\n");
        }
    }

    private void appendMemoryContext(StringBuilder sb, Map<String, Object> state) {
        if (!longTermMemoryProperties.isEnabled()) return;
        try {
            String sessionId = (String) state.getOrDefault("sessionId", "");
            String userInput = (String) state.getOrDefault("userInput", "");
            if (userInput == null || userInput.isBlank()) return;

            List<MemoryVO> memories = memoryService.retrieveMemories(
                    userInput, List.of(MemoryType.WRITING_PREFERENCE), sessionId, null);

            if (!memories.isEmpty()) {
                sb.append("\n## 用户写作偏好（长期记忆）\n");
                for (MemoryVO m : memories) {
                    sb.append("- ").append(m.getContent()).append("\n");
                }
            }
        } catch (Exception e) {
            log.debug("注入长期记忆到规划prompt失败: {}", e.getMessage());
        }
    }

    private String extractFinalOutput(Map<String, Object> accumulated) {
        String finalOutput = (String) accumulated.get("finalOutput");
        if (finalOutput != null && !finalOutput.isBlank()) {
            return finalOutput;
        }
        finalOutput = (String) accumulated.getOrDefault("polishedContent", "");
        if (!finalOutput.isBlank()) {
            return finalOutput;
        }
        finalOutput = (String) accumulated.getOrDefault("draft", "");
        if (!finalOutput.isBlank()) {
            return finalOutput;
        }
        finalOutput = (String) accumulated.getOrDefault("researchReport", "");
        return finalOutput;
    }

    private Map<String, Object> finishWithDirectAnswer(Map<String, Object> state) {
        WorkerAgent directAnswer = workerRegistry.get(properties.getFallbackWorker());
        if (directAnswer != null) {
            try {
                Map<String, Object> result = directAnswer.execute(new HashMap<>(state));
                result.put("currentNode", "SupervisorNode");
                return result;
            } catch (Exception e) {
                log.warn("[SupervisorNode] direct_answer Worker 执行也失败", e);
            }
        }
        return Map.of(
                "finalOutput", state.getOrDefault("finalOutput", ""),
                "currentNode", "SupervisorNode"
        );
    }

    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
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
