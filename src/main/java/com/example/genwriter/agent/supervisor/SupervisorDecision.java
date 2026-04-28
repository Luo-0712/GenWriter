package com.example.genwriter.agent.supervisor;

/**
 * Supervisor LLM 的结构化决策输出
 */
public record SupervisorDecision(
        String action,
        String workerName,
        String reasoning,
        String finalOutput
) {
    public static final String CALL_WORKER = "CALL_WORKER";
    public static final String FINISH = "FINISH";
}
