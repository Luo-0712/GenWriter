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
            "direct_answer", "直接回答",
            "writing_skill_extractor", "写作技巧提取"
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

                if ("intent_recognition".equals(workerName)) {
                    List<String> adjusted = ensurePlanMatchesIntent(steps, accumulated);
                    if (adjusted != steps) {
                        publishStatus(sessionId, "【调整】根据意图调整执行计划");
                        log.info("[SupervisorNode] 意图识别后调整计划: {} -> {}", steps, adjusted);
                        steps = adjusted;
                    }
                }

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

        if (hasWritingSteps(steps) && isFallingBackToResearch(accumulated, finalOutput)) {
            log.warn("[SupervisorNode] 写作计划包含draft/polish但最终输出仅为调研报告，中间步骤可能失败，使用direct_answer包装");
            accumulated.put("context", "请基于以下调研报告生成完整回答：\n\n" + finalOutput);
            return finishWithDirectAnswer(accumulated);
        }

        accumulated.put("finalOutput", finalOutput);
        accumulated.put("currentNode", "SupervisorNode");

        publishStatus(sessionId, "任务完成");
        return accumulated;
    }

    private Object buildWorkerOutputSummary(String workerName, Map<String, Object> result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("worker", workerName);
        summary.put("displayName", WORKER_DISPLAY_NAMES.getOrDefault(workerName, workerName));
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
            summary.put("verdict", result.get("reviewResult"));
        }
        if (result.containsKey("reviewFeedback")) {
            String fb = (String) result.get("reviewFeedback");
            summary.put("feedback", fb != null ? truncate(fb, 100) : "");
        }
        if (result.containsKey("reviewCount")) {
            summary.put("reviewCount", result.get("reviewCount"));
        }
        if (result.containsKey("searchRounds")) {
            summary.put("searchRounds", result.get("searchRounds"));
        }
        if (result.containsKey("researchSources")) {
            try {
                List<?> sources = objectMapper.readValue((String) result.get("researchSources"), List.class);
                summary.put("sourcesCount", sources.size());
            } catch (Exception ignored) {}
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
                String reasoning = root.path("reasoning").asText("");
                log.info("[SupervisorNode] LLM返回FINISH，但强制先执行intent_recognition再判断: reasoning={}", reasoning);
                return ExecutionPlan.of(List.of("intent_recognition", "direct_answer"),
                        "LLM returned FINISH, forcing minimal plan through intent_recognition");
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

    private List<String> ensurePlanMatchesIntent(List<String> steps, Map<String, Object> accumulated) {
        String intent = (String) accumulated.getOrDefault("intent", "");
        if ("STYLE_LEARNING".equals(intent)) {
            log.info("[SupervisorNode] 检测到STYLE_LEARNING意图，调整计划为写作技巧提取");
            return List.of("intent_recognition", "writing_skill_extractor");
        }
        if (!"WRITING_TASK".equals(intent)) {
            return steps;
        }

        List<String> remaining = steps.subList(1, steps.size());
        boolean hasWritingStages = remaining.stream()
                .anyMatch(s -> "outline".equals(s) || "draft".equals(s) || "polish".equals(s));
        if (hasWritingStages) {
            String kbId = (String) accumulated.getOrDefault("kbId", "");
            if (kbId != null && !kbId.isBlank() && !remaining.contains("researcher")) {
                log.info("[SupervisorNode] 检测到WRITING_TASK+kbId但计划缺少researcher，自动补充");
                List<String> adjusted = new ArrayList<>();
                adjusted.add("intent_recognition");
                adjusted.add("researcher");
                adjusted.addAll(remaining);
                return adjusted;
            }
            return steps;
        }

        log.info("[SupervisorNode] 检测到WRITING_TASK但计划缺少写作阶段，自动补充");
        List<String> adjusted = new ArrayList<>();
        adjusted.add("intent_recognition");
        if (remaining.contains("researcher")) {
            adjusted.add("researcher");
        }
        adjusted.add("outline");
        adjusted.add("draft");
        adjusted.add("polish");
        adjusted.add("review");
        return adjusted;
    }

    private boolean hasWritingSteps(List<String> steps) {
        return steps.stream().anyMatch(s -> "draft".equals(s) || "polish".equals(s) || "outline".equals(s));
    }

    private boolean isFallingBackToResearch(Map<String, Object> accumulated, String finalOutput) {
        if (finalOutput == null || finalOutput.isBlank()) return false;
        String researchReport = (String) accumulated.getOrDefault("researchReport", "");
        if (!finalOutput.equals(researchReport)) return false;
        return String.valueOf(accumulated.getOrDefault("polishedContent", "")).isBlank()
                && String.valueOf(accumulated.getOrDefault("draft", "")).isBlank();
    }

    private String buildPlanPrompt(Map<String, Object> state) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前状态\n");
        sb.append("- userInput: ").append(truncate((String) state.getOrDefault("userInput", ""), 500)).append("\n");
        sb.append("- kbId: ").append(state.getOrDefault("kbId", "")).append("\n");

        String writingType = (String) state.getOrDefault("writingType", "AUTO");
        sb.append("- writingType: ").append(writingType).append("\n");

        appendStateSummary(sb, state);
        appendMemoryContext(sb, state);

        // 用户显式指定了模式，作为规划硬约束
        if (!"AUTO".equals(writingType)) {
            String constraint = switch (writingType) {
                case "CREATE" -> "用户指定了「新建文档」模式，计划必须包含 intent_recognition → outline → draft → polish → review 流程。";
                case "CONTINUE" -> "用户指定了「续写」模式，计划必须包含 intent_recognition → draft（续写）→ polish → review 流程。";
                case "POLISH" -> "用户指定了「润色优化」模式，计划必须包含 intent_recognition → polish → review 流程。";
                case "KNOWLEDGE_QA" -> "用户指定了「知识问答」模式，计划应包含 intent_recognition → direct_answer 流程。";
                default -> "";
            };
            if (!constraint.isEmpty()) {
                sb.append("\n## 用户指定模式约束\n").append(constraint).append("\n");
            }
        }

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
                    userInput, List.of(MemoryType.WRITING_PREFERENCE), sessionId);

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
                state.putAll(result);
                state.put("currentNode", "SupervisorNode");
                return state;
            } catch (Exception e) {
                log.warn("[SupervisorNode] direct_answer Worker 执行也失败", e);
            }
        }
        state.put("currentNode", "SupervisorNode");
        return state;
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
