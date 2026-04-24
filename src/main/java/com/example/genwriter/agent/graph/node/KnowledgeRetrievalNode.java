package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识库检索节点
 * 根据用户输入和知识库ID检索相关文本片段
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalNode implements NodeAction {

    private final KnowledgeBaseTool knowledgeBaseTool;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value("userInput", String.class).orElse("");
        String kbId = state.value("kbId", String.class).orElse("");

        if (kbId == null || kbId.isBlank()) {
            log.debug("知识库检索跳过: kbId 为空");
            return Map.of("currentNode", "KnowledgeRetrievalNode");
        }

        log.debug("知识库检索: kbId={}, query={}", kbId, userInput);

        String result = knowledgeBaseTool.searchKnowledgeBase(userInput, kbId, 5);

        log.debug("知识库检索完成: resultLength={}", result.length());

        return Map.of(
                "context", result,
                "currentNode", "KnowledgeRetrievalNode"
        );
    }
}
