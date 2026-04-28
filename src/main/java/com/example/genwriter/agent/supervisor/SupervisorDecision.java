package com.example.genwriter.agent.supervisor;

public record SupervisorDecision(
        String action,
        String reasoning,
        String finalOutput
) {
    public static final String PLAN = "PLAN";
    public static final String FINISH = "FINISH";
}
