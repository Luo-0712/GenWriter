package com.example.genwriter.agent.react;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯逻辑 ReAct 循环：thought → action → observation 逐步推进。
 *
 * <p>本类不直接持有任何 Spring/LLM/SSE 依赖，所有外部能力以函数形式注入，便于单测 mock。
 * 调用方（{@code SupervisorNode}）负责把 LLM 决策、worker 执行、SSE/chain 发布、observation 摘要
 * 等行为适配为 {@link Decider} / {@link WorkerExecutor} / {@link ObservationSink} 三个回调。
 *
 * <p>语义对应计划 react-only-supervisor.md：替代原 generatePlan + while-loop 的"开局一次规划全 plan"，
 * 改为每步 LLM 决策下一步。maxIterations 上限兜底；连续决策失败达到阈值时通过 {@link Result#fallback()}
 * 通知调用方回退到旧 plan-then-execute 路径。
 */
@Slf4j
public class ReactLoop {

    /** 单步决策器：输入当前 state 与已执行历史，输出下一个 ReactStep。返回 null 视为决策失败。 */
    @FunctionalInterface
    public interface Decider {
        ReactStep decide(Map<String, Object> state, List<Map<String, Object>> history);
    }

    /** Worker 执行器：输入 worker 名与当前 state，返回 worker 产物（将被合并回 state）。返回 null 视为 worker 不存在/失败。 */
    @FunctionalInterface
    public interface WorkerExecutor {
        Map<String, Object> execute(String workerName, Map<String, Object> state) throws Exception;
    }

    /** 每步观察事件回调：把 thought/action/observation 等发布到 SSE/chain，与原 while-loop 行为对齐。 */
    @FunctionalInterface
    public interface StepHook {
        /**
         * @param phase     BEFORE / AFTER / SKIP / ERROR
         * @param workerName 当步 action（finish 时为 null）
         * @param state      当前 state（BEFORE 为执行前，AFTER 为合并后）
         * @param observation 该步 observation 摘要（仅 AFTER 有值）
         * @param error      仅 ERROR 有值
         * @param step       当步决策（含 thought / action / reasoning）；BEFORE 为执行前决策，
         *                   AFTER/SKIP/ERROR 为同一步决策，finish 步或 decider 失败时为 null
         */
        void onStep(String phase, String workerName, Map<String, Object> state,
                    Object observation, String error, ReactStep step);
    }

    public enum Phase { BEFORE, AFTER, SKIP, ERROR }

    /** 循环结束原因。 */
    public enum Termination { FINISH, MAX_ITERATIONS, FALLBACK }

    public static final int DEFAULT_MAX_CONSECUTIVE_FAILURES = 3;

    private final int maxIterations;
    private final int maxConsecutiveFailures;

    public ReactLoop(int maxIterations) {
        this(maxIterations, DEFAULT_MAX_CONSECUTIVE_FAILURES);
    }

    public ReactLoop(int maxIterations, int maxConsecutiveFailures) {
        this.maxIterations = Math.max(1, maxIterations);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
    }

    /**
     * 执行 ReAct 循环。
     *
     * @param state       可变 state，worker 产物会 putAll 合并回去
     * @param decider     单步决策器
     * @param executor    worker 执行器
     * @param stepHook    每步事件回调（可为 null）
     * @return 循环结果，包含终止原因、执行历史与是否需要回退
     */
    public Result run(Map<String, Object> state,
                      Decider decider,
                      WorkerExecutor executor,
                      StepHook stepHook) {
        List<Map<String, Object>> history = new ArrayList<>();
        int consecutiveFailures = 0;
        boolean fallback = false;

        for (int i = 0; i < maxIterations; i++) {
            ReactStep step;
            try {
                step = decider.decide(state, history);
            } catch (Exception e) {
                log.warn("[ReactLoop] decider 异常: {}", e.getMessage());
                step = null;
            }

            if (step == null) {
                consecutiveFailures++;
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    log.warn("[ReactLoop] 连续 {} 次决策失败，触发回退", consecutiveFailures);
                    fallback = true;
                    break;
                }
                continue;
            }
            // 决策成功后重置连续失败计数
            consecutiveFailures = 0;

            if (step.isFinish() || step.action() == null || step.action().isBlank()) {
                log.info("[ReactLoop] 第 {} 步 FINISH: reasoning={}", i, step.reasoning());
                return new Result(Termination.FINISH, history, false);
            }

            String workerName = step.action();
            if (stepHook != null) {
                stepHook.onStep(Phase.BEFORE.name(), workerName, state, null, null, step);
            }

            Map<String, Object> result;
            try {
                result = executor.execute(workerName, state);
            } catch (Exception e) {
                log.error("[ReactLoop] worker {} 执行异常: {}", workerName, e.getMessage(), e);
                if (stepHook != null) {
                    stepHook.onStep(Phase.ERROR.name(), workerName, state, null, e.getMessage(), step);
                }
                // 与原 while-loop 一致：异常不终止循环，跳过继续
                continue;
            }

            if (result == null) {
                // worker 不存在：与原 :223 跳过逻辑一致
                log.warn("[ReactLoop] worker 不存在或返回 null: {}", workerName);
                if (stepHook != null) {
                    stepHook.onStep(Phase.SKIP.name(), workerName, state, null, null, step);
                }
                continue;
            }

            // observation 写回 state
            state.putAll(result);

            // observation 摘要写入 history（供下一步决策）
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("worker", workerName);
            observation.put("thought", step.thought());
            observation.put("reasoning", step.reasoning());
            observation.put("result", result);
            history.add(observation);

            if (stepHook != null) {
                stepHook.onStep(Phase.AFTER.name(), workerName, state, observation, null, step);
            }
        }

        if (fallback) {
            return new Result(Termination.FALLBACK, history, true);
        }
        // 达到 maxIterations 上限：调用方负责降级（如 finishWithDirectAnswer）
        return new Result(Termination.MAX_ITERATIONS, history, false);
    }

    /**
     * 循环结果。
     *
     * @param termination 终止原因
     * @param history     已执行步骤的 observation 历史
     * @param fallback    是否需要回退到旧 plan-then-execute 路径
     */
    public record Result(Termination termination, List<Map<String, Object>> history, boolean fallback) {
        public boolean shouldFallback() {
            return fallback;
        }
    }
}
