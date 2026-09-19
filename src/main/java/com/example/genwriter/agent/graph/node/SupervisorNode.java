package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chain.ThoughtChainPublisher;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.memory.LongTermMemoryPromptFormatter;
import com.example.genwriter.agent.memory.LongTermMemoryProperties;
import com.example.genwriter.agent.react.ReactLoop;
import com.example.genwriter.agent.react.ReactStep;
import com.example.genwriter.agent.skill.NovelWritingPromptSupport;
import com.example.genwriter.agent.skill.SkillService;
import com.example.genwriter.agent.skill.WritingGenreProfile;
import com.example.genwriter.agent.skill.WritingGenreResolver;
import com.example.genwriter.agent.streaming.ReasoningChatModelDecorator;
import com.example.genwriter.agent.streaming.ReasoningChatModelDecorator.ReasoningCallResult;
import com.example.genwriter.agent.supervisor.ExecutionPlan;
import com.example.genwriter.agent.supervisor.SupervisorDecision;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.agent.supervisor.SupervisorSystemPromptProvider;
import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.supervisor.worker.ReviewWorker;
import com.example.genwriter.agent.tool.SessionContextHolder;
import com.example.genwriter.message.ChainNode;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.dto.response.MemoryVO;
import com.example.genwriter.model.enums.MemoryType;
import com.example.genwriter.service.LongTermMemoryService;
import com.example.genwriter.service.SseService;
import com.example.genwriter.service.WritingOutputSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
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
    private final LongTermMemoryPromptFormatter memoryPromptFormatter;
    private final LongTermMemoryProperties longTermMemoryProperties;
    private final ThoughtChainPublisher chainPublisher;
    private final SkillService skillService;
    private final WritingOutputSettingsService writingOutputSettingsService;
    private final ReasoningChatModelDecorator reasoningChatModelDecorator;

    private ChatClient chatClient;
    private String systemPrompt;
    private ChatClient reactChatClient;
    private String reactSystemPrompt;
    private ReactLoop reactLoop;
    /** read_skill_detail 工具回调：决策调用（callWithReasoning）需携带，供装饰器完成工具循环。 */
    private FunctionCallback readSkillDetailCallback;

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

    record ReadSkillInput(String name) {}

    @PostConstruct
    void init() {
        this.readSkillDetailCallback = FunctionToolCallback
                .builder("read_skill_detail",
                        (java.util.function.Function<ReadSkillInput, String>) this::readSkillDetail)
                .description("读取指定 Skill 的完整 Markdown 内容。" +
                        "当任务匹配某个 Skill 时调用，获取详细的工作流和写作经验后再规划。")
                .inputType(ReadSkillInput.class)
                .build();

        this.chatClient = chatClientFactory.create(properties.getTemperature())
                .mutate()
                .defaultTools(readSkillDetailCallback)
                .build();
        this.systemPrompt = promptProvider.buildSystemPrompt();

        // ReAct 模式：复用同一 chatClient（带 read_skill_detail 工具），仅 system prompt 不同
        this.reactChatClient = this.chatClient;
        this.reactSystemPrompt = promptProvider.buildReactSystemPrompt();
        this.reactLoop = new ReactLoop(
                properties.getMaxIterations(),
                properties.getReact().getMaxConsecutiveFailures());
    }

    private String readSkillDetail(ReadSkillInput input) {
        String skillName = input != null && input.name() != null ? input.name() : "";
        log.info("[Supervisor] 读取 skill 详情: {}", skillName);
        String sessionId = SessionContextHolder.get();
        String spanId = null;
        if (sessionId != null && !sessionId.isBlank()) {
            spanId = chainPublisher.publishToolStart(sessionId, "read_skill_detail",
                    Map.of("name", skillName));
        }
        try {
            String detail = skillService.readSkillDetail(skillName);
            if (spanId != null) {
                chainPublisher.publishToolComplete(sessionId, spanId, "read_skill_detail",
                        Map.of("name", skillName, "contentLength", detail != null ? detail.length() : 0));
            }
            return detail;
        } catch (Exception e) {
            if (spanId != null) {
                chainPublisher.publishToolError(sessionId, spanId, "read_skill_detail", e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");

        Map<String, Object> accumulated = new HashMap<>();
        accumulated.put("sessionId", sessionId);
        String userInput = state.value("userInput", String.class).orElse("");
        accumulated.put("userInput", userInput);
        accumulated.put("kbId", state.value("kbId", String.class).orElse(""));
        WritingGenreProfile genreProfile = WritingGenreResolver.resolve(userInput);
        boolean markdownEnabled = writingOutputSettingsService.isMarkdownEnabled();
        accumulated.put("writingGenre", genreProfile.genre().name());
        accumulated.put("genreLabel", genreProfile.displayName());
        accumulated.put("markdownEnabled", markdownEnabled);
        accumulated.put("outputFormat", writingOutputSettingsService.currentFormat());
        String writingType = state.value("writingType", String.class).orElse("CREATE");
        String forcedWritingType = NovelWritingPromptSupport.forcedWritingType(userInput);
        if (forcedWritingType != null && ("AUTO".equalsIgnoreCase(writingType) || writingType.isBlank())) {
            writingType = forcedWritingType;
        }
        accumulated.put("writingType", writingType);
        accumulated.put("context", state.value("context", String.class).orElse(""));
        accumulated.put("documentId", state.value("documentId", String.class).orElse(""));
        String selectedDocumentContent = state.value("selectedDocumentContent", String.class).orElse("");
        if (!selectedDocumentContent.isBlank()) {
            accumulated.put("selectedDocumentContent", selectedDocumentContent);
            accumulated.put("draft", state.value("draft", String.class).orElse(selectedDocumentContent));
        }
        state.value("selectedDocumentTitle", String.class)
                .ifPresent(title -> accumulated.put("selectedDocumentTitle", title));
        state.value("selectedDocumentVersion", Integer.class)
                .ifPresent(version -> accumulated.put("selectedDocumentVersion", version));
        String webSearchStr = state.value("webSearch", String.class).orElse("true");
        boolean webSearch = !"false".equalsIgnoreCase(webSearchStr);
        accumulated.put("webSearch", webSearch);

        String supervisorNodeId = chainPublisher.publishStart(sessionId, "任务规划",
                ChainNode.Type.PLANNING, null,
                Map.of("userInput", truncate((String) accumulated.getOrDefault("userInput", ""), 200)));

        // ===== ReAct 主路径：单步决策循环；异常/连续失败回退到旧 plan-then-execute =====
        List<String> steps;
        if (properties.getReact().isEnabled()) {
            ReactLoop.Result reactResult = runReactLoop(state, accumulated, sessionId, supervisorNodeId);

            if (reactResult.shouldFallback()) {
                log.warn("[SupervisorNode] ReAct 连续决策失败，回退到旧 plan-then-execute 路径");
                chainPublisher.publishComplete(sessionId, supervisorNodeId,
                        Map.of("action", "FALLBACK_TO_PLAN", "reasoning", "ReAct 决策失败，回退旧路径"));
                // 落入下方旧 plan 路径
                steps = null;
            } else if (reactResult.termination() == ReactLoop.Termination.MAX_ITERATIONS) {
                log.warn("[SupervisorNode] ReAct 达到 maxIterations({}) 上限，降级为直接回答", properties.getMaxIterations());
                chainPublisher.publishComplete(sessionId, supervisorNodeId,
                        Map.of("action", "MAX_ITERATIONS", "reasoning", "达到步数上限"));
                return finishWithDirectAnswer(accumulated);
            } else {
                // FINISH：进入最终产物提取，steps 取已执行 worker 名（用于 fallback 判定）
                chainPublisher.publishComplete(sessionId, supervisorNodeId,
                        Map.of("action", "FINISH", "reasoning", "ReAct 完成",
                                "steps", reactResult.history().stream()
                                        .map(h -> String.valueOf(h.get("worker"))).toList()));
                steps = reactResult.history().stream()
                        .map(h -> String.valueOf(h.get("worker")))
                        .toList();

                String finalOutput = extractFinalOutput(accumulated);
                if (hasWritingSteps(steps) && isFallingBackToResearch(accumulated, finalOutput)) {
                    log.warn("[SupervisorNode] ReAct 写作流程最终输出仅为调研报告，使用direct_answer包装");
                    accumulated.put("context", "请基于以下调研报告生成完整回答：\n\n" + finalOutput);
                    return finishWithDirectAnswer(accumulated);
                }

                accumulated.put("finalOutput", finalOutput);
                accumulated.put("currentNode", "SupervisorNode");
                publishStatus(sessionId, "任务完成");
                return accumulated;
            }
        } else {
            steps = null;
        }

        // ===== 旧路径：plan-then-execute（fallback / 开关关闭时走此分支，行为与重构前完全一致） =====
        ExecutionPlan plan;
        SessionContextHolder.set(sessionId, supervisorNodeId, "supervisor");
        try {
            plan = generatePlan(state, accumulated);
        } finally {
            SessionContextHolder.clear();
        }
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

        steps = plan.steps();
        chainPublisher.publishComplete(sessionId, supervisorNodeId,
                Map.of("steps", steps, "reasoning", plan.reasoning()));

        publishStatus(sessionId, "【规划】执行计划: " + String.join(" → ", steps));
        log.info("[SupervisorNode] 执行计划: steps={}, reasoning={}", steps, plan.reasoning());

        int planIndex = 0;
        int replanCount = 0;
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

                ExecutionPlan newPlan;
                SessionContextHolder.set(sessionId, replanNodeId, "supervisor");
                try {
                    newPlan = replan(state, accumulated);
                } finally {
                    SessionContextHolder.clear();
                }
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

            publishStatus(sessionId, "正在调用 " + displayName + "...");
            log.info("[SupervisorNode] 执行 Worker: name={}, step={}/{}", workerName, planIndex + 1, steps.size());

            try {
                Map<String, Object> result;
                // 与 ReAct 路径一致：步骤节点由 worker 自己发布，supervisor 不再发同名包裹节点。
                SessionContextHolder.set(sessionId, supervisorNodeId, workerName);
                try {
                    result = worker.execute(new HashMap<>(accumulated));
                } finally {
                    SessionContextHolder.clear();
                }
                accumulated.putAll(result);

                if ("intent_recognition".equals(workerName)) {
                    List<String> adjusted = ensurePlanMatchesIntent(steps, accumulated);
                    if (adjusted != steps) {
                        publishStatus(sessionId, "【调整】根据意图调整执行计划");
                        log.info("[SupervisorNode] 意图识别后调整计划: {} -> {}", steps, adjusted);
                        steps = adjusted;
                    }
                }

                planIndex++;
            } catch (Exception e) {
                log.error("[SupervisorNode] Worker 执行失败: name={}, error={}", workerName, e.getMessage(), e);
                publishStatus(sessionId, "步骤 " + displayName + " 执行异常，跳过继续...");
                planIndex++;
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

    /**
     * ReAct 主路径：调用 {@link ReactLoop}，把 LLM 单步决策、worker 执行、SSE/chain 发布适配为回调。
     *
     * <p>行为与旧 while-loop 对齐：每步 publishStatus + SessionContextHolder 设置；步骤节点本身由 worker 发布，
     * supervisor 不再发同名包裹节点（否则轨迹里每步都是两层同名节点）。首步护栏：尚未执行 intent_recognition
     * 时强制首步为 intent_recognition，复用旧路径的护栏语义。
     *
     * <p>decider 读取 ReactLoop 维护的 history（每项含 worker/thought/reasoning/result）作为 observation。
     */
    private ReactLoop.Result runReactLoop(OverAllState state, Map<String, Object> accumulated,
                                          String sessionId, String supervisorNodeId) {
        int[] stepCounter = {0};
        boolean[] intentDone = {false};
        ContentPhase[] contentPhase = {ContentPhase.IDLE};

        // 硬护栏强制出的步骤要能和模型自主决策区分（identity 语义，与下方 decisionSpanIds 一致）
        Set<ReactStep> guardSteps = Collections.newSetFromMap(new IdentityHashMap<>());

        ReactLoop.Decider decider = (s, hist) -> {
            ReactStep forced = forcedContentStep(intentDone[0], contentPhase[0], reviewCountOf(s));
            if (forced != null) {
                guardSteps.add(forced);
                return forced;
            }
            return decideNextReactStep(s, hist);
        };

        ReactLoop.WorkerExecutor executor = (workerName, s) -> {
            WorkerAgent worker = workerRegistry.get(workerName);
            if (worker == null) {
                log.warn("[SupervisorNode][ReAct] Worker 不存在: name={}, 跳过", workerName);
                return null;
            }
            String displayName = WORKER_DISPLAY_NAMES.getOrDefault(workerName, workerName);
            int stepNo = ++stepCounter[0];
            publishStatus(sessionId, "正在调用 " + displayName + "...");
            log.info("[SupervisorNode][ReAct] 执行 Worker: name={}, step={}", workerName, stepNo);

            Map<String, Object> result;
            // 步骤节点只由 worker 自己发布（其 publishStart 的 parentId 传 null，按当前 span 挂载）：
            // supervisor 再发一个同名包裹节点会让轨迹里出现 "意图识别 > 意图识别" 的双层重复。
            SessionContextHolder.set(sessionId, supervisorNodeId, workerName);
            try {
                result = worker.execute(new HashMap<>(s));
            } catch (Exception e) {
                log.error("[SupervisorNode][ReAct] Worker 执行失败: name={}, error={}", workerName, e.getMessage(), e);
                publishStatus(sessionId, "步骤 " + displayName + " 执行异常，跳过继续...");
                throw e;
            } finally {
                SessionContextHolder.clear();
            }

            // 首步 intent_recognition 完成后，标记护栏已完成
            if ("intent_recognition".equals(workerName)) {
                intentDone[0] = true;
            }

            // 内容流水线状态推进（仅在 worker 执行成功后）：
            // draft 之后必须重新 polish（ReviewWorker 只评审 polishedContent，draft 一变
            // 润色稿即过期）；polish 之后必须 review；review 之后恢复自由决策。
            switch (workerName) {
                case "draft" -> contentPhase[0] = ContentPhase.POLISH_AFTER_DRAFT;
                case "polish" -> contentPhase[0] = ContentPhase.REVIEW_AFTER_POLISH;
                case "review" -> contentPhase[0] = ContentPhase.IDLE;
                default -> { /* outline/researcher/direct_answer 不改正文，阶段保持不变 */ }
            }

            // 陈旧评审结论清理：评审结论只对下一次决策有效。非 review worker 执行后
            // state 中残留的 REVISE_* 会诱导决策 LLM 重复同一修订动作，这里直接移除
            if (!"review".equals(workerName)) {
                s.remove("reviewResult");
                s.remove("reviewFeedback");
            }

            return result;
        };

        return reactLoop.run(accumulated, decider, executor, new ReactLoop.StepHook() {
            @Override
            public void onStep(String phase, String workerName, Map<String, Object> s,
                               Object observation, String error, ReactStep step) {
                if (step == null) return;
                // BEFORE：把单步决策的 thought / reasoning / action 作为 LLM span 推到 trace 树
                if (ReactLoop.Phase.BEFORE.name().equals(phase)) {
                    String spanId = chainPublisher.publishReactDecisionTrace(
                            sessionId, supervisorNodeId,
                            step.thought(), step.reasoning(), step.action(),
                            guardSteps.contains(step) ? ThoughtChainPublisher.DECISION_GUARD
                                    : ThoughtChainPublisher.DECISION_REACT);
                    decisionSpanIds.put(step, spanId);
                } else {
                    // AFTER / SKIP / ERROR：结束该决策 span，摘要沿用决策依据（护栏步用硬护栏理由），
                    // 否则 COMPLETED 会退化成兜底文案「已完成」并覆盖 STARTED 阶段展示的思考
                    String spanId = decisionSpanIds.remove(step);
                    if (spanId != null) {
                        if (ReactLoop.Phase.ERROR.name().equals(phase)) {
                            chainPublisher.publishTraceError(sessionId, spanId, error);
                        } else {
                            chainPublisher.publishReactDecisionComplete(sessionId, spanId,
                                    guardSteps.contains(step) ? step.reasoning() : step.thought());
                        }
                    }
                }
            }

            private final java.util.IdentityHashMap<ReactStep, String> decisionSpanIds = new java.util.IdentityHashMap<>();
        });
    }

    /**
     * 单步 LLM 决策：当前 state + 已执行 history → 下一个 ReactStep。
     * 解析失败返回 null（由 ReactLoop 计入连续失败次数，达阈值触发回退）。
     */
    private ReactStep decideNextReactStep(Map<String, Object> state, List<Map<String, Object>> history) {
        // 把 history 中的 result 摘要为可读 observation 文本，避免灌全文
        List<Object> observationSummaries = new ArrayList<>();
        for (Map<String, Object> h : history) {
            Object result = h.get("result");
            if (result instanceof Map<?, ?> rm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rm2 = (Map<String, Object>) rm;
                observationSummaries.add(buildWorkerOutputSummary(String.valueOf(h.get("worker")), rm2));
            } else {
                observationSummaries.add(h);
            }
        }

        String userPrompt = promptProvider.buildReactStepPrompt(state, observationSummaries);

        String response;
        String chainOfThought = null;
        try {
            // 决策改走推理装饰器聚合调用：采集模型真实思维链（reasoning_content），
            // 并携带 read_skill_detail 工具（工具循环在装饰器内完成）
            FunctionCallingOptions options = FunctionCallingOptions.builder()
                    .temperature(properties.getTemperature())
                    .functionCallbacks(List.of(readSkillDetailCallback))
                    .build();
            ReasoningCallResult result = reasoningChatModelDecorator.callWithReasoning(new Prompt(
                    List.of(new SystemMessage(reactSystemPrompt), new UserMessage(userPrompt)), options));
            response = result.content();
            chainOfThought = result.reasoningContent();
        } catch (Exception e) {
            log.error("[SupervisorNode][ReAct] LLM 决策调用失败", e);
            return null;
        }

        try {
            String json = stripMarkdownCodeBlock(response);
            JsonNode root = objectMapper.readTree(json);
            String action = root.path("action").asText("");
            if (action.isBlank()) {
                log.warn("[SupervisorNode][ReAct] 决策缺少 action: response={}", response);
                return null;
            }
            String thought = root.path("thought").asText("");
            String reasoning = root.path("reasoning").asText("");
            // 真实思维链与模型自述决策依据合并，随 ReactStep 传给 StepHook，
            // 由 publishReactDecisionTrace 写入「ReAct 决策」span 的 reasoningContent
            return new ReactStep(thought, action, null, combineReasoning(chainOfThought, reasoning));
        } catch (Exception e) {
            log.warn("[SupervisorNode][ReAct] 决策解析失败: response={}", response, e);
            return null;
        }
    }

    /**
     * 真实思维链（reasoning_content）与模型自述 reasoning 合并：
     * 前端「ReAct 决策」节点的「模型思考过程」区块优先展示模型内部思考，自述以标注补充。
     */
    private static String combineReasoning(String chainOfThought, String selfReported) {
        boolean hasChain = chainOfThought != null && !chainOfThought.isBlank();
        boolean hasSelf = selfReported != null && !selfReported.isBlank();
        if (hasChain && hasSelf) {
            return chainOfThought.trim() + "\n\n[决策自述] " + selfReported.trim();
        }
        if (hasChain) return chainOfThought.trim();
        return hasSelf ? selfReported.trim() : null;
    }

    /** 读取 state 中 ReviewWorker 写入的已评审轮数，缺失时按 0 处理。 */
    private static int reviewCountOf(Map<String, Object> state) {
        Object value = state.get("reviewCount");
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** ReAct 内容流水线阶段：跟踪最近一次成功执行的内容写 worker，决定下一步的强制衔接。 */
    enum ContentPhase {
        /** 无待衔接步骤，交由决策 LLM 自由选择 */
        IDLE,
        /** draft 刚执行成功：polishedContent 已过期，必须先执行 polish 刷新 */
        POLISH_AFTER_DRAFT,
        /** polish 刚执行成功：必须先执行 review（评审额度耗尽则以最新润色稿定稿） */
        REVIEW_AFTER_POLISH
    }

    /**
     * 内容流水线硬护栏（纯函数，便于单测）。
     *
     * <p>不变式：ReviewWorker 只评审 {@code polishedContent}，而 polish 的输出总是由当前 draft
     * 派生，因此 draft 一变 polishedContent 即过期——draft 之后必须先 polish 才能 review；
     * polish 之后必须先 review。该护栏堵住两类偏差：决策 LLM 依据残留的 REVISE_* 判决反复选择
     * 同一写作 worker（如连续 polish/draft），以及在 draft 修订后直接评审过期的润色稿。
     *
     * @return 应强制执行的步骤；{@code null} 表示无强制步骤，交由决策 LLM
     */
    static ReactStep forcedContentStep(boolean intentDone, ContentPhase phase, int reviewCount) {
        if (!intentDone) {
            return new ReactStep(
                    "首步需先识别意图与 writingType",
                    "intent_recognition",
                    null,
                    "首步护栏：强制 intent_recognition");
        }
        return switch (phase) {
            case POLISH_AFTER_DRAFT -> new ReactStep(
                    "正文已更新，润色稿已过期，需重新润色",
                    "polish",
                    null,
                    "硬护栏：draft 之后强制 polish（ReviewWorker 只评审 polishedContent）");
            case REVIEW_AFTER_POLISH -> {
                if (reviewCount >= ReviewWorker.MAX_REVIEW_ROUNDS) {
                    yield new ReactStep(
                            "评审轮数已达上限，停止修订",
                            ReactStep.FINISH,
                            null,
                            "硬护栏：reviewCount=" + reviewCount + " 已达上限，以最新润色稿定稿");
                }
                yield new ReactStep(
                        "润色已更新，需先评审再决定后续",
                        "review",
                        null,
                        "硬护栏：polish 之后强制 review");
            }
            case IDLE -> null;
        };
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
        sb.append("- writingGenre: ").append(state.getOrDefault("writingGenre", "UNKNOWN"))
                .append(" (").append(state.getOrDefault("genreLabel", "通用写作")).append(")\n");
        sb.append("- outputFormat: ").append(state.getOrDefault("outputFormat", "markdown")).append("\n");

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
        sb.append("- writingGenre: ").append(state.getOrDefault("writingGenre", "UNKNOWN"))
                .append(" (").append(state.getOrDefault("genreLabel", "通用写作")).append(")\n");
        sb.append("- outputFormat: ").append(state.getOrDefault("outputFormat", "markdown")).append("\n");

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
        if (state.containsKey("selectedDocumentContent")) {
            String content = (String) state.get("selectedDocumentContent");
            sb.append("- selectedDocument: ")
                    .append(state.getOrDefault("selectedDocumentTitle", "未命名文稿"))
                    .append(" V")
                    .append(state.getOrDefault("selectedDocumentVersion", "?"))
                    .append(", ")
                    .append(content != null ? content.length() + " chars" : "null")
                    .append("\n");
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
                sb.append("\n## 长期记忆上下文\n");
                sb.append(memoryPromptFormatter.format(memories)).append("\n");
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
        // 如果 cleaned 不是以 { 开头，尝试提取第一个 JSON 对象
        if (!cleaned.startsWith("{")) {
            int braceStart = cleaned.indexOf('{');
            if (braceStart >= 0) {
                // 从第一个 { 开始，寻找匹配的最后一个 }
                int lastBrace = cleaned.lastIndexOf('}');
                if (lastBrace > braceStart) {
                    cleaned = cleaned.substring(braceStart, lastBrace + 1);
                }
            }
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
