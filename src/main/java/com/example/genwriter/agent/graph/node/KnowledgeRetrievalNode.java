package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
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
    private final SseService sseService;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");
        String kbId = state.value("kbId", String.class).orElse("");

        if (kbId == null || kbId.isBlank()) {
            log.debug("知识库检索跳过: kbId 为空");
            publishStatus(sessionId, "【知识库检索】未指定知识库，跳过检索");
            return Map.of("currentNode", "KnowledgeRetrievalNode");
        }

        log.debug("知识库检索: kbId={}, query={}", kbId, userInput);
        publishStatus(sessionId, "【知识库检索】正在检索相关知识片段...");

        String rawResult = knowledgeBaseTool.searchKnowledgeBase(userInput, kbId, 5);
        String formattedContext = formatContext(rawResult);

        log.debug("知识库检索完成: rawLength={}, formattedLength={}", rawResult.length(), formattedContext.length());
        publishStatus(sessionId, "【知识库检索】完成，检索结果长度=" + formattedContext.length() + " 字符");

        return Map.of(
                "context", formattedContext,
                "currentNode", "KnowledgeRetrievalNode"
        );
    }

    /**
     * 将原始 JSON 检索结果格式化为 Markdown 引用块，便于 LLM 理解
     */
    private String formatContext(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "";
        }

        // 如果结果已经是格式化的文本（非 JSON 错误），直接包装
        if (rawResult.startsWith("{\"error\"")) {
            return "> **知识库检索提示**: " + extractErrorMessage(rawResult);
        }

        if (rawResult.startsWith("{\"results\":[],\"message\"")) {
            return "> **知识库检索提示**: 未找到相关知识片段";
        }

        // 简单包装原始 JSON，使其在 prompt 中更清晰
        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库检索结果\n\n");
        sb.append("以下是从知识库中检索到的相关片段，按相关性排序：\n\n");
        sb.append(rawResult);
        return sb.toString();
    }

    private String extractErrorMessage(String json) {
        try {
            int start = json.indexOf("\":\"");
            if (start > 0) {
                int end = json.indexOf("\"", start + 3);
                if (end > 0) {
                    return json.substring(start + 3, end);
                }
            }
        } catch (Exception e) {
            log.debug("提取错误消息失败");
        }
        return "检索失败";
    }

    private void publishStatus(String sessionId, String statusText) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_EXECUTING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }
}
