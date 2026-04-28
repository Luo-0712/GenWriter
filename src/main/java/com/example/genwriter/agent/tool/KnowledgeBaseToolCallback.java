package com.example.genwriter.agent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

@Slf4j
public class KnowledgeBaseToolCallback implements Function<KnowledgeBaseToolCallback.KnowledgeSearchInput, String> {

    private final KnowledgeBaseTool knowledgeBaseTool;

    public record KnowledgeSearchInput(String query, String kbId, Integer topK) {
        public KnowledgeSearchInput {
            if (topK == null || topK < 1) topK = 5;
            if (topK > 20) topK = 20;
        }
    }

    public KnowledgeBaseToolCallback(KnowledgeBaseTool knowledgeBaseTool) {
        this.knowledgeBaseTool = knowledgeBaseTool;
    }

    @Override
    public String apply(KnowledgeSearchInput input) {
        if (input.kbId() == null || input.kbId().isBlank()) {
            return "{\"error\": \"知识库ID不能为空，请确认是否有可用的知识库\"}";
        }
        if (input.query() == null || input.query().isBlank()) {
            return "{\"error\": \"检索关键词不能为空，请提供具体的检索关键词\"}";
        }

        log.info("[KnowledgeBaseTool] 检索知识库: kbId={}, query={}, topK={}", input.kbId(), input.query(), input.topK());

        try {
            return knowledgeBaseTool.searchKnowledgeBase(input.query(), input.kbId(), input.topK());
        } catch (Exception e) {
            log.error("[KnowledgeBaseTool] 知识库检索失败: kbId={}, query={}", input.kbId(), input.query(), e);
            return "{\"error\": \"知识库检索失败: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
