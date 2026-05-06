package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.supervisor.worker.ResearcherWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ResearcherNode implements NodeAction {

    private final ResearcherWorker researcherWorker;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Map<String, Object> stateMap = new HashMap<>();
        state.value("sessionId", String.class).ifPresent(v -> stateMap.put("sessionId", v));
        state.value("userInput", String.class).ifPresent(v -> stateMap.put("userInput", v));
        state.value("kbId", String.class).ifPresent(v -> stateMap.put("kbId", v));
        state.value("intent", String.class).ifPresent(v -> stateMap.put("intent", v));
        state.value("writingType", String.class).ifPresent(v -> stateMap.put("writingType", v));
        state.value("context", String.class).ifPresent(v -> stateMap.put("context", v));

        Map<String, Object> result = researcherWorker.execute(stateMap);

        Map<String, Object> update = new HashMap<>(result);
        update.put("currentNode", "ResearcherNode");
        return update;
    }
}
