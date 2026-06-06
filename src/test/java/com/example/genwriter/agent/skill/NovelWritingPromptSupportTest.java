package com.example.genwriter.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NovelWritingPromptSupportTest {

    @Test
    void novelRequest_ShouldDetectChineseAndEnglishSignals() {
        assertTrue(NovelWritingPromptSupport.isNovelRequest("写两章科技文小说"));
        assertTrue(NovelWritingPromptSupport.isNovelRequest("continue chapter 2 of this sci-fi novel"));
        assertFalse(NovelWritingPromptSupport.isNovelRequest("解释量子纠缠的基本概念"));
    }

    @Test
    void forcedWritingType_ShouldOnlyForceCreativeNovelRequests() {
        assertEquals("CREATE", NovelWritingPromptSupport.forcedWritingType("写两章科技文小说"));
        assertEquals("CONTINUE", NovelWritingPromptSupport.forcedWritingType("请续写下一章科幻小说"));
        assertNull(NovelWritingPromptSupport.forcedWritingType("解释科幻小说中的曲率引擎概念"));
        assertNull(NovelWritingPromptSupport.forcedWritingType("分析这本科幻小说第一章的结构"));
    }

    @Test
    void constraints_ShouldAskForNarrativeProseWhenNovelRequested() {
        String draftConstraint = NovelWritingPromptSupport.draftConstraint("请续写一章科幻小说");
        String reviewConstraint = NovelWritingPromptSupport.reviewConstraint("请续写一章科幻小说");

        assertTrue(draftConstraint.contains("direct narrative prose"));
        assertTrue(draftConstraint.contains("Do not output an outline"));
        assertTrue(reviewConstraint.contains("REVISE_DRAFT"));
    }
}
