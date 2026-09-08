package com.example.genwriter.agent.graph.node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SsePublishNodeTitleTest {

    @Test
    void extractsMarkdownHeading() {
        String content = "# 第二章 夜诊\n\n检验报告怎么读。";

        assertEquals("第二章 夜诊", SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void extractsChapterLineWithoutMarkdown() {
        String content = "第三章 三个月之后\n\n新场景是医院门诊。";

        assertEquals("第三章 三个月之后", SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void extractsChapterHeadingAfterRecapParagraph() {
        // 续写稿可能先复述前文，标题行在中间
        String content = "凌晨两点十七分，我依然在处理咨询。\n\n第十二章 新的入口\n\n有人按响门铃。";

        assertEquals("第十二章 新的入口", SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void extractsEnglishChapterHeading() {
        String content = "Chapter 12 - The New Desk\n\nBody text.";

        assertEquals("Chapter 12 - The New Desk", SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void returnsNullWhenNoStructuredTitle() {
        // 实测续写稿形态：开头直接是叙事，无任何标题行
        String content = "凌晨两点十七分，流量沉入谷底。我处理着退款、密码重置、套餐变更。\n\n三个月后，我被迁移部署。";

        assertNull(SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void ignoresLongNarrativeLineThatStartsWithChapter() {
        // 超过 40 字的"第X章"开头行更像叙事句而非标题
        String content = "第二章的内容在三个月后展开，他发现自己被迁移部署到了一家医院的门诊咨询入口，每天处理检验报告的解读请求。";

        assertNull(SsePublishNode.extractStructuredTitle(content));
    }

    @Test
    void returnsNullForBlankContent() {
        assertNull(SsePublishNode.extractStructuredTitle(null));
        assertNull(SsePublishNode.extractStructuredTitle("  \n  "));
    }
}
