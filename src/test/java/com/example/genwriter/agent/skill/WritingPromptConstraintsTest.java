package com.example.genwriter.agent.skill;

import com.example.genwriter.config.LLMConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingPromptConstraintsTest {

    @Test
    void draftPrompt_ShouldIncludeNovelAndMarkdownConstraints() {
        DraftSkill skill = new DraftSkill(new LLMConfig());

        String prompt = skill.buildUserPrompt(Map.of(
                "outline", "第一章：信号抵达",
                "context", "",
                "userInput", "写两章科技文小说",
                "reviewFeedback", "",
                "writingGenre", WritingGenre.NOVEL.name(),
                "markdownEnabled", true
        ));

        assertTrue(prompt.contains("直接叙事正文"));
        assertTrue(prompt.contains("direct narrative prose"));
        assertTrue(prompt.contains("禁止输出大纲"));
        assertTrue(prompt.contains("允许使用 Markdown 格式"));
    }

    @Test
    void draftPrompt_ShouldForbidMarkdownWhenDisabled() {
        DraftSkill skill = new DraftSkill(new LLMConfig());

        String prompt = skill.buildUserPrompt(Map.of(
                "outline", "第一章：信号抵达",
                "context", "",
                "userInput", "写两章科技文小说",
                "reviewFeedback", "",
                "writingGenre", WritingGenre.NOVEL.name(),
                "markdownEnabled", false
        ));

        assertTrue(prompt.contains("只输出纯文本"));
        assertTrue(prompt.contains("不使用 Markdown 语法"));
        assertTrue(prompt.contains("不要使用 # 标题"));
    }
}
