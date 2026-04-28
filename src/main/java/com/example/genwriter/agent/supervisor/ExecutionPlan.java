package com.example.genwriter.agent.supervisor;

import java.util.List;

public record ExecutionPlan(
        List<String> steps,
        String reasoning,
        int restartFrom
) {
    public ExecutionPlan {
        if (restartFrom < 0) restartFrom = 0;
    }

    public static ExecutionPlan of(List<String> steps, String reasoning) {
        return new ExecutionPlan(steps, reasoning, 0);
    }
}
