package com.example.genwriter.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.skill.IntentRecognitionSkill;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.service.SseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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

    private static final double TEMPERATURE = 0.1;

    private final ChatClientFactory chatClientFactory;
    private final ObjectMapper objectMapper;
    private final IntentRecognitionSkill skill;
    private final SseService sseService;

    private ChatClient chatClient;

    @PostConstruct
    void initChatClient() {
        this.chatClient = chatClientFactory.create(TEMPERATURE);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sessionId = state.value("sessionId", String.class).orElse("");
        String userInput = state.value("userInput", String.class).orElse("");

        log.debug("意图识别: userInput={}", userInput);
        publishStatus(sessionId, "【意图识别】正在分析用户需求...");

        String userPrompt = skill.buildUserPrompt(Map.of("userInput", userInput));
        String response = chatClient.prompt()
                .system(skill.systemPrompt())
                .user(userPrompt)
                .call()
                .content();

        IntentResult result;
        try {
            result = parseIntent(response);
        } catch (Exception e) {
            log.error("意图识别解析失败，降级为 UNKNOWN: response={}", response, e);
            result = new IntentResult("UNKNOWN", "CREATE", "解析失败，降级处理");
        }

        log.info("意图识别结果: intent={}, writingType={}, reason={}",
                result.intent(), result.writingType(), result.reason());
        Map<String, String> detail = Map.of(
                "意图", result.intent(),
                "写作类型", result.writingType(),
                "分析", result.reason()
        );
        publishStatusWithData(sessionId, "【意图识别】结果: " + result.intent() + " / " + result.writingType(), detail);

        return Map.of(
                "intent", result.intent(),
                "writingType", result.writingType(),
                "currentNode", "IntentRecognitionNode"
        );
    }

    private IntentResult parseIntent(String response) throws Exception {
        String json = stripMarkdownCodeBlock(response);
        return objectMapper.readValue(json, IntentResult.class);
    }

    /**
     * 去除可能的 Markdown 代码块标记
     */
    private String stripMarkdownCodeBlock(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        // 如果内容被包裹在纯 ``` 中，也一并去除
        if (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private void publishStatus(String sessionId, String statusText) {
        publishStatusWithData(sessionId, statusText, null);
    }

    private void publishStatusWithData(String sessionId, String statusText, Object data) {
        if (sessionId.isBlank()) return;
        try {
            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .data(data)
                            .build())
                    .build());
        } catch (Exception e) {
            log.debug("SSE 状态推送失败: {}", e.getMessage());
        }
    }

    public record IntentResult(String intent, String writingType, String reason) {
    }
}
