package com.example.genwriter.agent.supervisor.worker;

import com.example.genwriter.agent.supervisor.WorkerAgent;
import com.example.genwriter.agent.supervisor.WorkerRegistry;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalWorker implements WorkerAgent {

    private final KnowledgeBaseTool knowledgeBaseTool;
    private final WorkerRegistry registry;

    @PostConstruct
    void init() {
        registry.register(this);
    }

    @Override
    public String name() {
        return "knowledge_retrieval";
    }

    @Override
    public String description() {
        return "从知识库检索与用户输入相关的文本片段，作为写作参考上下文";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> state) throws Exception {
        String userInput = (String) state.getOrDefault("userInput", "");
        String kbId = (String) state.getOrDefault("kbId", "");

        if (kbId == null || kbId.isBlank()) {
            log.debug("知识库检索跳过: kbId 为空");
            return Map.of("context", "");
        }

        String rawResult = knowledgeBaseTool.searchKnowledgeBase(userInput, kbId, 5);
        String formatted = formatContext(rawResult);
        log.info("知识库检索完成: length={}", formatted.length());
        return Map.of("context", formatted);
    }

    private String formatContext(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) return "";
        if (rawResult.startsWith("{\"error\"")) return "> **知识库检索提示**: 检索失败";
        if (rawResult.startsWith("{\"results\":[],\"message\"")) return "> **知识库检索提示**: 未找到相关知识片段";

        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库检索结果\n\n");
        sb.append("以下是从知识库中检索到的相关片段：\n\n");
        sb.append(rawResult);
        return sb.toString();
    }
}
