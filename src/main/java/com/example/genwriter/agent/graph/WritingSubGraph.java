package com.example.genwriter.agent.graph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.SubGraphNode;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.example.genwriter.agent.graph.node.DraftGenerationNode;
import com.example.genwriter.agent.graph.node.PolishNode;
import com.example.genwriter.agent.graph.node.ReviewNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

// 搁置
/**
 * 写作子图
 * 封装完整的写作流水线：正文生成 → 润色 → 评审（含循环打磨）
 * 输入状态：outline, userInput, context（可选）
 * 输出状态：finalOutput, polishedContent, draft, reviewResult, reviewFeedback
 *
 * 该子图作为主图的嵌套节点使用，状态通过 OverAllState 与主图共享。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WritingSubGraph implements SubGraphNode {

    private final KeyStrategyFactory keyStrategyFactory;
    private final DraftGenerationNode draftGenerationNode;
    private final PolishNode polishNode;
    private final ReviewNode reviewNode;

    @Override
    public String id() {
        return "writing_subgraph";
    }

    @Override
    public StateGraph subGraph() {
        try {
            return new StateGraph("WritingSubGraph", keyStrategyFactory)
                    .addNode("draft", node_async(draftGenerationNode))
                    .addNode("polish", node_async(polishNode))
                    .addNode("review", node_async(reviewNode))

                    // 子图内部流转
                    .addEdge(StateGraph.START, "draft")
                    .addEdge("draft", "polish")
                    .addEdge("polish", "review")

                    // 评审后：通过则结束子图，不通过则循环打磨
                    .addConditionalEdges("review", edge_async((EdgeAction) state -> {
                        String reviewResult = state.value("reviewResult", String.class).orElse("PASS");
                        log.debug("写作子图评审路由: reviewResult={}", reviewResult);
                        return reviewResult;
                    }), Map.of(
                            "PASS", StateGraph.END,
                            "REVISE_DRAFT", "draft",
                            "REVISE_POLISH", "polish"
                    ));
        } catch (Exception e) {
            throw new RuntimeException("构建写作子图失败", e);
        }
    }
}
