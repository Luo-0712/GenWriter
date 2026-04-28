package com.example.genwriter.agent.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.example.genwriter.agent.graph.node.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Slf4j
@Configuration
public class GraphConfig {

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
            strategies.put("researchReport", new ReplaceStrategy());
            strategies.put("researchSources", new ReplaceStrategy());
            strategies.put("searchRounds", new ReplaceStrategy());
            return strategies;
        };
    }

    @Bean
    @Qualifier("intentRouterGraph")
    public StateGraph intentRouterGraph(
            KeyStrategyFactory keyStrategyFactory,
            IntentRecognitionNode intentRecognitionNode,
            DirectAnswerNode directAnswerNode,
            PolishNode polishNode,
            OutlineGenerationNode outlineGenerationNode,
            SsePublishNode ssePublishNode,
            WritingSubGraph writingSubGraph,
            ResearcherNode researcherNode) throws Exception {

        return new StateGraph("IntentRouterGraph", keyStrategyFactory)
                .addNode("intent_recognition", node_async(intentRecognitionNode))
                .addNode("direct_answer", node_async(directAnswerNode))
                .addNode("polish", node_async(polishNode))
                .addNode("outline", node_async(outlineGenerationNode))
                .addNode("sse_publish", node_async(ssePublishNode))
                .addNode("writing_subgraph", writingSubGraph.subGraph())
                .addNode("researcher", node_async(researcherNode))

                .addEdge(StateGraph.START, "intent_recognition")

                .addConditionalEdges("intent_recognition", edge_async((EdgeAction) state -> {
                    String intent = state.value("intent", String.class).orElse("UNKNOWN");
                    log.debug("意图路由: intent={}", intent);

                    return switch (intent) {
                        case "WRITING_TASK" -> "outline";
                        case "KNOWLEDGE_QA" -> "direct_answer";
                        case "POLISH_TASK" -> "polish";
                        case "RESEARCH_TASK" -> "researcher";
                        default -> "direct_answer";
                    };
                }), Map.of(
                        "outline", "outline",
                        "direct_answer", "direct_answer",
                        "polish", "polish",
                        "researcher", "researcher"
                ))

                .addConditionalEdges("direct_answer", edge_async((EdgeAction) state -> "sse_publish"),
                        Map.of("sse_publish", "sse_publish"))

                .addEdge("outline", "writing_subgraph")
                .addEdge("writing_subgraph", "sse_publish")

                .addEdge("polish", "sse_publish")

                .addEdge("researcher", "direct_answer")

                .addEdge("sse_publish", StateGraph.END);
    }
}
