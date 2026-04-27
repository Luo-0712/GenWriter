package com.example.genwriter.service;

import com.example.genwriter.config.LLMConfig;
import com.example.genwriter.mapper.TaskSessionMapper;
import com.example.genwriter.message.SseMessage;
import com.example.genwriter.model.entity.TaskSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TitleGenerationService {

    private final ChatClient chatClient;
    private final LLMConfig llmConfig;
    private final TaskSessionMapper taskSessionMapper;
    private final SseService sseService;

    private static final String DEFAULT_TITLE = "新对话";
    private static final int MAX_TITLE_LENGTH = 20;

    @Async("taskExecutor")
    public void generateAndSetTitle(String sessionId, String userMessage) {
        try {
            TaskSession session = taskSessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("标题生成：会话不存在, sessionId={}", sessionId);
                return;
            }

            if (!DEFAULT_TITLE.equals(session.getTitle())) {
                log.debug("标题生成：会话已有自定义标题, 跳过, sessionId={}, title={}",
                        sessionId, session.getTitle());
                return;
            }

            String prompt = llmConfig.getPrompts().getTitleSummaryPrompt()
                    .replace("{userMessage}", userMessage);

            String generatedTitle = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (generatedTitle != null && !generatedTitle.isBlank()) {
                generatedTitle = generatedTitle.trim()
                        .replaceAll("^[\"「『《]|[」』》\"]$", "")
                        .trim();

                if (generatedTitle.length() > MAX_TITLE_LENGTH) {
                    generatedTitle = generatedTitle.substring(0, MAX_TITLE_LENGTH);
                }

                if (generatedTitle.isBlank()) {
                    generatedTitle = truncateForTitle(userMessage);
                }
            } else {
                generatedTitle = truncateForTitle(userMessage);
            }

            TaskSession updateEntity = TaskSession.builder()
                    .id(sessionId)
                    .title(generatedTitle)
                    .build();
            taskSessionMapper.updateById(updateEntity);

            sseService.publish(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.TITLE_UPDATED)
                    .payload(SseMessage.Payload.builder()
                            .data(generatedTitle)
                            .build())
                    .build());

            log.info("标题生成完成: sessionId={}, title={}", sessionId, generatedTitle);

        } catch (Exception e) {
            log.error("标题生成失败: sessionId={}, error={}", sessionId, e.getMessage(), e);

            String fallbackTitle = truncateForTitle(userMessage);
            try {
                TaskSession updateEntity = TaskSession.builder()
                        .id(sessionId)
                        .title(fallbackTitle)
                        .build();
                taskSessionMapper.updateById(updateEntity);

                sseService.publish(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.TITLE_UPDATED)
                        .payload(SseMessage.Payload.builder()
                                .data(fallbackTitle)
                                .build())
                        .build());
            } catch (Exception ex) {
                log.error("降级标题更新失败: sessionId={}", sessionId, ex);
            }
        }
    }

    private String truncateForTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return DEFAULT_TITLE;
        }
        String text = userMessage.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_TITLE_LENGTH) {
            return text.substring(0, MAX_TITLE_LENGTH) + "...";
        }
        return text;
    }
}
