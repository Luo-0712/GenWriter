package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通用问答节点
 * 直接调用 LLM 回答用户问题，无特定 system prompt
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectAnswerNode implements NodeAction {

    private final ChatClient chatClient;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value("userInput", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");
        String sessionId = state.value("sessionId", String.class).orElse("");

        log.debug("通用问答: userInput={}, contextLength={}", userInput, context.length());

        String prompt = buildPrompt(userInput, context);
        String response = chatClient.prompt().user(prompt).call().content();

        log.debug("通用问答完成: responseLength={}", response.length());

        return Map.of(
                "finalOutput", response,
                "currentNode", "DirectAnswerNode"
        );
    }

    private String buildPrompt(String userInput, String context) {
        if (context != null && !context.isBlank()) {
            return """
                    基于以下上下文信息回答用户问题：

                    上下文：
                    %s

                    用户问题：%s
                    """.formatted(context, userInput);
        }
        return userInput;
    }
}
