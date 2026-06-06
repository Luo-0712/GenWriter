package com.example.genwriter.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WritingGenreResolverTest {

    @Test
    void resolve_ShouldDetectCoreGenres() {
        assertEquals(WritingGenre.NOVEL, WritingGenreResolver.resolve("写两章科技文小说").genre());
        assertEquals(WritingGenre.TECH_DOC, WritingGenreResolver.resolve("写一份 API 技术文档").genre());
        assertEquals(WritingGenre.ACADEMIC, WritingGenreResolver.resolve("生成一篇学术论文摘要").genre());
        assertEquals(WritingGenre.REPORT, WritingGenreResolver.resolve("写一份行业调研报告").genre());
    }

    @Test
    void forcedWritingType_ShouldOnlyForceCreativeNovelRequests() {
        assertEquals("CREATE", WritingGenreResolver.forcedWritingType("写两章科技文小说"));
        assertEquals("CONTINUE", WritingGenreResolver.forcedWritingType("请续写下一章科幻小说"));
        assertNull(WritingGenreResolver.forcedWritingType("分析这本科幻小说第一章的结构"));
    }

    @Test
    void profile_ShouldMarkNovel() {
        WritingGenreProfile profile = WritingGenreResolver.resolve("continue chapter 2 of this sci-fi novel");
        assertTrue(profile.isNovel());
    }
}
