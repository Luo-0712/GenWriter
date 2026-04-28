package com.example.genwriter.agent.graph;

import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.example.genwriter.agent.graph.node.SsePublishNode;
import com.example.genwriter.agent.graph.node.SupervisorNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 多智能体监督者模式图配置
 * 简化的图拓扑：START -> supervisor -> sse_publish -> END
 * 所有路由决策由 SupervisorNode 内部 ReAct 循环完成
 */
@Slf4j
@Configuration
public class SupervisorGraphConfig {

    @Bean
    @Qualifier("supervisorGraph")
    public StateGraph supervisorGraph(
            KeyStrategyFactory keyStrategyFactory,
            SupervisorNode supervisorNode,
            SsePublishNode ssePublishNode) throws Exception {

        return new StateGraph("SupervisorGraph", keyStrategyFactory)
                .addNode("supervisor", node_async(supervisorNode))
                .addNode("sse_publish", node_async(ssePublishNode))
                .addEdge(StateGraph.START, "supervisor")
                .addEdge("supervisor", "sse_publish")
                .addEdge("sse_publish", StateGraph.END);
    }
}
