package com.example.genwriter.agent.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.genwriter.agent.graph.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * StateGraph 工作流配置（扁平化结构）
 * 所有节点直接注册在同一个意图路由图中，避免子图嵌套导致的编译问题
 */
@Slf4j
@Configuration
public class GraphConfig {

    /**
     * 状态字段更新策略工厂
     * 所有字段默认使用 ReplaceStrategy（直接覆盖）
     */
    @Bean
    public KeyStrategyFactory keyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new java.util.HashMap<>();
            strategies.put("sessionId", new ReplaceStrategy());
            strategies.put("documentId", new ReplaceStrategy());
            strategies.put("userInput", new ReplaceStrategy());
            strategies.put("kbId", new ReplaceStrategy());
            strategies.put("intent", new ReplaceStrategy());
            strategies.put("writingType", new ReplaceStrategy());
            strategies.put("context", new ReplaceStrategy());
            strategies.put("outline", new ReplaceStrategy());
            strategies.put("draft", new ReplaceStrategy());
            strategies.put("polishedContent", new ReplaceStrategy());
            strategies.put("finalOutput", new ReplaceStrategy());
            strategies.put("currentNode", new ReplaceStrategy());
            strategies.put("retryCount", new ReplaceStrategy());
            strategies.put("errorMessage", new ReplaceStrategy());
            strategies.put("reviewResult", new ReplaceStrategy());
            strategies.put("reviewFeedback", new ReplaceStrategy());
            strategies.put("reviewCount", new ReplaceStrategy());
            return strategies;
        };
    }

    /**
     * 统一的意图路由工作流图
     * 
     */
    @Bean
    public StateGraph intentRouterGraph(
            KeyStrategyFactory keyStrategyFactory,
            IntentRecognitionNode intentRecognitionNode,
            KnowledgeRetrievalNode knowledgeRetrievalNode,
            DirectAnswerNode directAnswerNode,
            PolishNode polishNode,
            OutlineGenerationNode outlineGenerationNode,
            DraftGenerationNode draftGenerationNode,
            SsePublishNode ssePublishNode,
            ReviewNode reviewNode) throws Exception {

        return new StateGraph("IntentRouterGraph", keyStrategyFactory)
                // 注册所有节点
                .addNode("intent_recognition", node_async(intentRecognitionNode))
                .addNode("knowledge_retrieval", node_async(knowledgeRetrievalNode))
                .addNode("direct_answer", node_async(directAnswerNode))
                .addNode("polish", node_async(polishNode))
                .addNode("outline", node_async(outlineGenerationNode))
                .addNode("draft", node_async(draftGenerationNode))
                .addNode("sse_publish", node_async(ssePublishNode))
                .addNode("review", node_async(reviewNode))

                // 起始边：从 START 到意图识别
                .addEdge(StateGraph.START, "intent_recognition")

                // 条件边1：意图识别后的路由决策
                .addConditionalEdges("intent_recognition", edge_async((EdgeAction) state -> {
                    String intent = state.value("intent", String.class).orElse("UNKNOWN");
                    String kbId = state.value("kbId", String.class).orElse("");
                    log.debug("意图路由: intent={}", intent);

                    return switch (intent) {
                        case "WRITING_TASK" -> "outline";
                        case "KNOWLEDGE_QA" -> (kbId != null && !kbId.isBlank()) ? "knowledge_retrieval" : "direct_answer";
                        case "POLISH_TASK" -> "polish";
                        default -> "direct_answer";
                    };
                }), Map.of(
                        "outline", "outline",
                        "knowledge_retrieval", "knowledge_retrieval",
                        "direct_answer", "direct_answer",
                        "polish", "polish"
                ))

                // 快速执行路径的边
                .addEdge("knowledge_retrieval", "direct_answer")

                // 条件边2：直接回答后的路由（所有路径都到 sse_publish）
                .addConditionalEdges("direct_answer", edge_async((EdgeAction) state -> "sse_publish"),
                        Map.of("sse_publish", "sse_publish"))

                // 完整写作路径的边（含评审循环）
                .addEdge("outline", "draft")
                .addEdge("draft", "polish")
                .addEdge("polish", "review")

                // 条件边3：评审后的路由决策（通过则发布，不通过则回退打磨）
                .addConditionalEdges("review", edge_async((EdgeAction) state -> {
                    String reviewResult = state.value("reviewResult", String.class).orElse("PASS");
                    log.debug("评审路由: reviewResult={}", reviewResult);
                    return reviewResult;
                }), Map.of(
                        "PASS", "sse_publish",
                        "REVISE_DRAFT", "draft",
                        "REVISE_POLISH", "polish"
                ))

                // 最终发布节点到 END
                .addEdge("sse_publish", StateGraph.END);
    }
}
