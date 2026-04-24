package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.event.ChatEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 意图识别节点
 * 分析用户输入，判断是通用问答还是写作任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognitionNode implements NodeAction {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value("userInput", String.class).orElse("");
        log.debug("意图识别: userInput={}", userInput);

        String prompt = buildIntentPrompt(userInput);
        String response = chatClient.prompt().user(prompt).call().content();

        IntentResult result;
        try {
            result = parseIntent(response);
        } catch (Exception e) {
            log.error("意图识别解析失败，降级为 UNKNOWN: response={}", response, e);
            result = new IntentResult("UNKNOWN", "CREATE", "解析失败，降级处理");
        }

        log.info("意图识别结果: intent={}, writingType={}, reason={}",
                result.intent(), result.writingType(), result.reason());

        return Map.of(
                "intent", result.intent(),
                "writingType", result.writingType(),
                "currentNode", "IntentRecognitionNode"
        );
    }

    private String buildIntentPrompt(String userInput) {
        return """
                请分析以下用户输入，判断其意图。可选意图：
                - GENERAL_QA: 通用问答、闲聊、简单咨询、与写作无关的问题
                - WRITING_TASK: 写作、创作、续写、生成大纲等写作相关任务
                - KNOWLEDGE_QA: 基于知识库问答（提到"知识库"、"文档"、"资料"等）
                - POLISH_TASK: 润色、优化、改写文本
                - UNKNOWN: 无法判断

                同时判断写作类型（如果是写作相关）：
                - CREATE: 新建文档/文章
                - CONTINUE: 续写已有文档
                - POLISH: 润色文本
                - KNOWLEDGE_QA: 知识库问答

                请严格按以下 JSON 格式输出，不要包含其他内容：
                {
                  "intent": "WRITING_TASK",
                  "writingType": "CREATE",
                  "reason": "用户要求写一篇关于AI的文章"
                }

                用户输入：%s
                """.formatted(userInput);
    }

    private IntentResult parseIntent(String response) throws Exception {
        // 清理可能的 markdown 代码块
        String json = response;
        if (json.contains("```")) {
            json = json.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }
        return objectMapper.readValue(json, IntentResult.class);
    }

    public record IntentResult(String intent, String writingType, String reason) {}
}
