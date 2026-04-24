package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 正文写作节点
 * 根据大纲生成文章正文
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftGenerationNode implements NodeAction {

    private final ChatClient chatClient;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String outline = state.value("outline", String.class).orElse("");
        String context = state.value("context", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String reviewFeedback = state.value("reviewFeedback", String.class).orElse("");

        log.debug("正文写作: outlineLength={}, hasFeedback={}", outline.length(), !reviewFeedback.isBlank());

        String prompt = buildPrompt(outline, context, userInput, reviewFeedback);
        String response = chatClient.prompt()
                .system("你是一位资深作家，擅长根据大纲撰写高质量、流畅的文章。请确保内容连贯、逻辑清晰、表达生动。")
                .user(prompt)
                .call()
                .content();

        log.debug("正文写作完成: draftLength={}", response.length());

        return Map.of(
                "draft", response,
                "currentNode", "DraftGenerationNode"
        );
    }

    private String buildPrompt(String outline, String context, String userInput, String reviewFeedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下大纲撰写完整的文章正文：\n\n");
        sb.append("大纲：\n").append(outline).append("\n\n");
        if (context != null && !context.isBlank()) {
            sb.append("参考信息：\n").append(context).append("\n\n");
        }
        sb.append("原始需求：").append(userInput).append("\n\n");
        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("【重要：上一轮评审反馈，请务必在本次写作中改进】\n");
            sb.append(reviewFeedback).append("\n\n");
        }
        sb.append("请直接输出文章正文，不需要额外的解释。");
        return sb.toString();
    }
}
