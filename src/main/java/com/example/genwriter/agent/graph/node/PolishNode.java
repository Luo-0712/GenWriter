package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 润色节点
 * 对文章进行润色优化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolishNode implements NodeAction {

    private final ChatClient chatClient;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String draft = state.value("draft", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String reviewFeedback = state.value("reviewFeedback", String.class).orElse("");

        // 如果是直接润色任务（POLISH），userInput 是要润色的内容
        String contentToPolish = draft.isBlank() ? userInput : draft;

        log.debug("润色: contentLength={}, hasFeedback={}", contentToPolish.length(), !reviewFeedback.isBlank());

        String prompt = buildPrompt(contentToPolish, reviewFeedback);
        String response = chatClient.prompt()
                .system("你是一位资深编辑，擅长对文章进行润色优化。请提升表达的准确性、流畅度和可读性，同时保持原意不变。")
                .user(prompt)
                .call()
                .content();

        log.debug("润色完成: polishedLength={}", response.length());

        return Map.of(
                "polishedContent", response,
                "finalOutput", response,
                "currentNode", "PolishNode"
        );
    }

    private String buildPrompt(String content, String reviewFeedback) {
        StringBuilder sb = new StringBuilder();
        sb.append("请对以下文本进行润色优化，提升表达质量：\n\n");
        sb.append("原文：\n").append(content).append("\n\n");
        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("【重要：上一轮评审反馈，请务必在本次润色中改进】\n");
            sb.append(reviewFeedback).append("\n\n");
        }
        sb.append("请直接输出润色后的文本，不需要额外的解释。");
        return sb.toString();
    }
}
