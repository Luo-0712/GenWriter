package com.example.genwriter.agent.supervisor;

import java.util.Map;

/**
 * Worker Agent 接口
 * 每个 Worker 封装一个独立的专业能力，由 Supervisor 动态调度
 */
public interface WorkerAgent {

    String name();

    String description();

    Map<String, Object> execute(Map<String, Object> state) throws Exception;
}
