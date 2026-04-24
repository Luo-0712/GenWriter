package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.config.LLMConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 大纲生成节点
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutlineGenerationNode implements NodeAction {

    private final ChatClient chatClient;
    private final LLMConfig llmConfig;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value("userInput", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");

        log.debug("大纲生成: userInput={}", userInput);

        String prompt = buildPrompt(userInput, context);
        String response = chatClient.prompt()
                .system("你是一位专业的写作大纲设计师，擅长根据用户需求设计清晰、结构化的文章大纲。")
                .user(prompt)
                .call()
                .content();

        log.debug("大纲生成完成: length={}", response.length());

        return Map.of(
                "outline", response,
                "currentNode", "OutlineGenerationNode"
        );
    }

    private String buildPrompt(String userInput, String context) {
        if (context != null && !context.isBlank()) {
            return """
                    请根据以下参考信息，为用户需求设计一份详细的文章大纲：

                    参考信息：
                    %s

                    用户需求：%s
                    """.formatted(context, userInput);
        }
        return "请为以下需求设计一份详细的文章大纲：\n\n" + userInput;
    }
}
