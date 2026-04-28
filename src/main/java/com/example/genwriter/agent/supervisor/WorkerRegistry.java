package com.example.genwriter.agent.supervisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WorkerRegistry {

    private final Map<String, WorkerAgent> workers = new ConcurrentHashMap<>();

    public void register(WorkerAgent worker) {
        workers.put(worker.name(), worker);
        log.info("Worker 已注册: name={}, description={}", worker.name(), worker.description());
    }

    public WorkerAgent get(String name) {
        return workers.get(name);
    }

    public Map<String, String> getWorkerDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (var entry : workers.entrySet()) {
            descriptions.put(entry.getKey(), entry.getValue().description());
        }
        return descriptions;
    }

    public boolean hasWorker(String name) {
        return workers.containsKey(name);
    }
}
