package com.example.genwriter.agent.react;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReactLoopTest {

    /** 构造一个始终按预设序列返回 step 的 decider；序列耗尽后返回 finish。 */
    private ReactLoop.Decider scriptedDecider(List<String> actions) {
        AtomicInteger idx = new AtomicInteger();
        return (state, history) -> {
            int i = idx.getAndIncrement();
            String action = i < actions.size() ? actions.get(i) : "finish";
            return new ReactStep("thought-" + i, action, null, "reason-" + i);
        };
    }

    /** 构造一个把 worker 名记入 ran 并返回 {produced: workerName} 的 executor。 */
    private ReactLoop.WorkerExecutor recordingExecutor(List<String> ran) {
        return (workerName, state) -> {
            ran.add(workerName);
            Map<String, Object> result = new HashMap<>();
            result.put("produced", workerName);
            return result;
        };
    }

    @Test
    void normalLoop_runsWorkersThenFinish() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.Result result = loop.run(state,
                scriptedDecider(List.of("intent_recognition", "outline", "draft")),
                recordingExecutor(ran),
                null);

        assertEquals(ReactLoop.Termination.FINISH, result.termination());
        assertFalse(result.shouldFallback());
        assertEquals(List.of("intent_recognition", "outline", "draft"), ran);
        // observation 写回 state
        assertEquals("draft", state.get("produced"));
        // history 记录三步
        assertEquals(3, result.history().size());
        assertEquals("intent_recognition", result.history().get(0).get("worker"));
    }

    @Test
    void finishImmediately_terminatesWithoutWorker() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.Result result = loop.run(state,
                scriptedDecider(List.of()), // 第一步即 finish
                recordingExecutor(ran),
                null);

        assertEquals(ReactLoop.Termination.FINISH, result.termination());
        assertTrue(ran.isEmpty(), "finish 时不应执行任何 worker");
        assertTrue(result.history().isEmpty());
    }

    @Test
    void workerNotExists_skipsAndContinues() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        // 第一步是不存在的 worker（executor 返回 null），第二步 finish
        ReactLoop.WorkerExecutor executor = (workerName, s) -> {
            if ("ghost".equals(workerName)) return null; // 模拟 worker 不存在
            ran.add(workerName);
            Map<String, Object> r = new HashMap<>();
            r.put("produced", workerName);
            return r;
        };

        ReactLoop.Result result = loop.run(state,
                scriptedDecider(List.of("ghost", "outline")),
                executor,
                null);

        assertEquals(ReactLoop.Termination.FINISH, result.termination());
        // ghost 被跳过，只跑了 outline
        assertEquals(List.of("outline"), ran);
        assertEquals(1, result.history().size());
    }

    @Test
    void workerThrows_skipsAndContinues() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.WorkerExecutor executor = (workerName, s) -> {
            if ("boom".equals(workerName)) throw new RuntimeException("worker exploded");
            ran.add(workerName);
            Map<String, Object> r = new HashMap<>();
            r.put("produced", workerName);
            return r;
        };

        ReactLoop.Result result = loop.run(state,
                scriptedDecider(List.of("boom", "outline")),
                executor,
                null);

        assertEquals(ReactLoop.Termination.FINISH, result.termination());
        assertEquals(List.of("outline"), ran, "异常 worker 被跳过，后续步骤继续");
    }

    @Test
    void maxIterationsReached_terminatesWithMaxIterations() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(3); // 上限 3
        Map<String, Object> state = new HashMap<>();

        // decider 永不 finish，一直返回 outline（实际会被 ReactLoop 限制在 3 步）
        ReactLoop.Decider decider = (s, history) ->
                new ReactStep("loop", "outline", null, "never finish");

        ReactLoop.Result result = loop.run(state, decider,
                recordingExecutor(ran), null);

        assertEquals(ReactLoop.Termination.MAX_ITERATIONS, result.termination());
        assertFalse(result.shouldFallback());
        assertEquals(3, ran.size(), "应正好执行 maxIterations 步");
        assertEquals(3, result.history().size());
    }

    @Test
    void consecutiveDeciderFailures_triggersFallback() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12, 3); // 连续 3 次失败回退
        Map<String, Object> state = new HashMap<>();

        // decider 始终返回 null（解析失败）
        ReactLoop.Decider decider = (s, history) -> null;

        ReactLoop.Result result = loop.run(state, decider,
                recordingExecutor(ran), null);

        assertEquals(ReactLoop.Termination.FALLBACK, result.termination());
        assertTrue(result.shouldFallback(), "应触发回退");
        assertTrue(ran.isEmpty(), "decider 全失败时不应执行任何 worker");
    }

    @Test
    void deciderFailureResetsOnSuccess() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12, 3);
        Map<String, Object> state = new HashMap<>();
        AtomicInteger call = new AtomicInteger();

        // 失败 2 次（未达阈值 3）→ 成功 1 次 → finish
        ReactLoop.Decider decider = (s, history) -> {
            int i = call.getAndIncrement();
            if (i < 2) return null; // 两次失败
            if (i == 2) return new ReactStep("ok", "outline", null, "recovered");
            return new ReactStep("done", "finish", null, "end");
        };

        ReactLoop.Result result = loop.run(state, decider,
                recordingExecutor(ran), null);

        assertEquals(ReactLoop.Termination.FINISH, result.termination());
        assertEquals(List.of("outline"), ran, "中间失败后成功恢复，仍能执行后续 worker");
    }

    @Test
    void deciderThrows_treatedAsFailure() {
        List<String> ran = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12, 2);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.Decider decider = (s, history) -> {
            throw new RuntimeException("LLM exploded");
        };

        ReactLoop.Result result = loop.run(state, decider,
                recordingExecutor(ran), null);

        assertEquals(ReactLoop.Termination.FALLBACK, result.termination());
        assertTrue(result.shouldFallback());
    }

    @Test
    void stepHook_receivesBeforeAfterPhases() {
        List<String> ran = new ArrayList<>();
        List<String> phases = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.StepHook hook = (phase, workerName, s, observation, error, step) ->
                phases.add(phase + ":" + workerName);

        loop.run(state,
                scriptedDecider(List.of("outline")),
                recordingExecutor(ran),
                hook);

        assertTrue(phases.contains("BEFORE:outline"));
        assertTrue(phases.contains("AFTER:outline"));
    }

    @Test
    void stepHook_receivesStepWithThoughtAndReasoning() {
        // BEFORE 阶段应能拿到完整 ReactStep（含 thought / reasoning / action），
        // 供调用方把决策依据推到前端 trace 树。
        List<String> ran = new ArrayList<>();
        List<ReactStep> beforeSteps = new ArrayList<>();
        List<ReactStep> afterSteps = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.StepHook hook = (phase, workerName, s, observation, error, step) -> {
            if (step == null) return;
            if (ReactLoop.Phase.BEFORE.name().equals(phase)) beforeSteps.add(step);
            if (ReactLoop.Phase.AFTER.name().equals(phase)) afterSteps.add(step);
        };

        loop.run(state,
                scriptedDecider(List.of("outline", "draft")),
                recordingExecutor(ran),
                hook);

        assertEquals(2, beforeSteps.size(), "每步 BEFORE 应收到 step");
        assertEquals(2, afterSteps.size(), "每步 AFTER 应收到 step");
        ReactStep first = beforeSteps.get(0);
        assertEquals("outline", first.action());
        assertEquals("thought-0", first.thought(), "BEFORE step 应透传 decider 产出的 thought");
        assertEquals("reason-0", first.reasoning(), "BEFORE step 应透传 decider 产出的 reasoning");
        // BEFORE 与 AFTER 收到的是同一个 step 引用
        assertSame(beforeSteps.get(0), afterSteps.get(0));
    }

    @Test
    void stepHook_errorAndSkipPhasesStillReceiveStep() {
        // worker 抛异常 / 不存在时，ERROR / SKIP 阶段也应收到该步 step
        List<ReactStep> errorSteps = new ArrayList<>();
        List<ReactStep> skipSteps = new ArrayList<>();
        ReactLoop loop = new ReactLoop(12);
        Map<String, Object> state = new HashMap<>();

        ReactLoop.WorkerExecutor executor = (workerName, s) -> {
            if ("boom".equals(workerName)) throw new RuntimeException("exploded");
            return null; // ghost 不存在
        };

        ReactLoop.StepHook hook = (phase, workerName, s, observation, error, step) -> {
            if (step == null) return;
            if (ReactLoop.Phase.ERROR.name().equals(phase)) errorSteps.add(step);
            if (ReactLoop.Phase.SKIP.name().equals(phase)) skipSteps.add(step);
        };

        loop.run(state,
                scriptedDecider(List.of("boom", "ghost", "finish")),
                executor,
                hook);

        assertEquals(1, errorSteps.size(), "boom 步应在 ERROR 阶段收到 step");
        assertEquals("boom", errorSteps.get(0).action());
        assertEquals(1, skipSteps.size(), "ghost 步应在 SKIP 阶段收到 step");
        assertEquals("ghost", skipSteps.get(0).action());
    }
}
