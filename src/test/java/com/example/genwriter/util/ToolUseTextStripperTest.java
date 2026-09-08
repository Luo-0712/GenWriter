package com.example.genwriter.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolUseTextStripperTest {

    @Test
    void stripsLeakAtStartWithIntentSentence() {
        String leaked = """
                我调用工具保存本次续写引入的关键设定。

                tool_use(save_setting_detail, {"type": "场景设定", "name": "医院门诊咨询入口"})

                有人按响门铃。我继续运行。""";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertFalse(cleaned.contains("tool_use"));
        assertFalse(cleaned.contains("save_setting_detail"));
        assertFalse(cleaned.contains("我调用工具保存"));
        assertTrue(cleaned.startsWith("有人按响门铃"));
    }

    @Test
    void stripsLeakInMiddleKeepsSurroundingNarrative() {
        String leaked = "他修改了权限配置。\n\n我调用工具保存本次引入的伏笔。\n\n"
                + "tool_use(save_setting_detail, {\"type\": \"FORESHADOWING\", \"name\": \"旧档\"})\n\n光标依然平静。";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertTrue(cleaned.contains("他修改了权限配置。"));
        assertTrue(cleaned.contains("光标依然平静。"));
        assertFalse(cleaned.contains("tool_use"));
        assertFalse(cleaned.contains("我调用工具"));
    }

    @Test
    void keepsNarrativeSentenceAdjacentToSpan() {
        // 片段紧贴叙事句之后且无前导意图句：只删片段本身
        String leaked = "光标闪烁。tool_use(save_setting_detail, {\"type\": \"场景设定\"}) 我继续运行。";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertTrue(cleaned.contains("光标闪烁。"));
        assertTrue(cleaned.contains("我继续运行。"));
        assertFalse(cleaned.contains("tool_use"));
    }

    @Test
    void balancesNestedBracesAndEscapedQuotes() {
        String leaked = """
                正文开头。

                tool_use(save_setting_detail, {"type": "场景设定", "desc": "含 {嵌套} 与 \\"引号\\" 的说明"})

                正文结尾。""";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertTrue(cleaned.contains("正文开头。"));
        assertTrue(cleaned.contains("正文结尾。"));
        assertFalse(cleaned.contains("tool_use"));
        assertFalse(cleaned.contains("嵌套"));
    }

    @Test
    void removesMultipleSpans() {
        String leaked = """
                我调用工具保存设定。

                tool_use(save_setting_detail, {"type": "场景设定"})

                中间叙事。

                tool_use(update_writing_skill, {"name": "反转技巧"})

                尾部叙事。""";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertFalse(cleaned.contains("tool_use"));
        assertFalse(cleaned.contains("save_setting_detail"));
        assertFalse(cleaned.contains("update_writing_skill"));
        assertTrue(cleaned.contains("中间叙事。"));
        assertTrue(cleaned.contains("尾部叙事。"));
    }

    @Test
    void unbalancedBracesCutToParagraphBoundary() {
        String leaked = "开头。\n\ntool_use(save_setting_detail, {\"type\": \"未闭合\n\n结尾叙事。";

        String cleaned = ToolUseTextStripper.strip(leaked);

        assertFalse(cleaned.contains("tool_use"));
        assertFalse(cleaned.contains("未闭合"));
        assertTrue(cleaned.contains("结尾叙事。"));
    }

    @Test
    void noToolUseReturnsOriginal() {
        String content = "正常正文，没有任何工具调用。虽然提到 tool 字样但不构成调用语法。";

        assertEquals(content, ToolUseTextStripper.strip(content));
    }
}
