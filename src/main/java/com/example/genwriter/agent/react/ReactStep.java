package com.example.genwriter.agent.react;

import java.util.Map;

/**
 * ReAct 单步决策结果。
 *
 * <p>由 LLM 在每一步产出：thought（对上一步 observation 的思考）→ action（下一个 worker 名称或 finish）→
 * reasoning（选该 action 的理由）。args 预留给未来传参，当前 ReAct 化阶段不使用。
 *
 * <p>对应计划 react-only-supervisor.md 中的"单步决策协议"。
 */
public record ReactStep(
        String thought,
        String action,
        Map<String, Object> args,
        String reasoning
) {

    /** 表示任务完成、退出循环的特殊 action。 */
    public static final String FINISH = "finish";

    public boolean isFinish() {
        return FINISH.equalsIgnoreCase(action);
    }
}
