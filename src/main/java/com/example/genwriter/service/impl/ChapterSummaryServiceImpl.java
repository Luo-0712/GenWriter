package com.example.genwriter.service.impl;

import com.example.genwriter.agent.chatclient.ChatClientFactory;
import com.example.genwriter.agent.supervisor.SupervisorModeProperties;
import com.example.genwriter.service.ChapterSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterSummaryServiceImpl implements ChapterSummaryService {

    private final ChatClientFactory chatClientFactory;
    private final SupervisorModeProperties supervisorProperties;

    @Override
    public String summarize(String userInput, String finalOutput, String writingType) {
        SupervisorModeProperties.ChapterContext properties = supervisorProperties.getChapterContext();
        if (!properties.isEnabled() || finalOutput == null || finalOutput.isBlank()) {
            return "";
        }

        try {
            ChatClient client = chatClientFactory.create(properties.getTemperature());
            String summary = client.prompt()
                    .system("""
                            你是长篇写作的章节连续性摘要器。
                            只基于给定章节内容总结已经发生的叙事状态，不新增设定，不纠错，不评价。
                            输出一段自然中文文段，不要列表、标题、编号或 Markdown。
                            必须覆盖：已发生内容、关键事实、角色/对象状态、未完成事项和下一章接续点。
                            """)
                    .user("""
                            用户本章写作要求：
                            %s

                            写作类型：%s

                            章节内容：
                            %s
                            """.formatted(safe(userInput), safe(writingType), finalOutput))
                    .call()
                    .content();
            return truncate(clean(summary), properties.getMaxSummaryChars());
        } catch (Exception e) {
            log.warn("章节摘要生成失败，跳过章节上下文: {}", e.getMessage());
            return "";
        }
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int limit = Math.max(1, maxLength);
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
