package com.example.genwriter.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剥离正文中泄漏的工具调用文本（文本级兜底清理）。
 *
 * <p>思考流（reasoning stream）路径不绑定结构化工具（见 ReasoningStreamingClient 请求体无 tools 字段），
 * 但 draft/outline 的 profile prompt 仍指示模型"必须调用 save_setting_detail 工具"，模型可能把调用
 * 意图以 {@code tool_use(name, {json})} 的形式连同前导说明句写进正文。本类在 worker 返回最终内容前
 * 做文本级兜底：按括号配平定位完整调用片段（容忍字符串与转义），并连带移除紧邻的"我调用工具…"类
 * 前导意图句。
 */
@Slf4j
public final class ToolUseTextStripper {

    private static final Pattern SPAN_START = Pattern.compile("(?:tool_use|tool_call|function_call)\\s*\\(");

    /** 前导意图句特征：同时出现"动作词"与"对象词"，且总长受限，避免误伤叙事句。 */
    private static final Pattern INTENT_HINT = Pattern.compile(
            "(调用|使用|执行|保存|写入|记录|存入|call|invoke|store).{0,20}"
                    + "(工具|tool_use|tool_call|save_setting|update_writing_skill|设定|伏笔|技巧|记忆|setting|skill|memory)",
            Pattern.CASE_INSENSITIVE);

    private static final String SENTENCE_ENDS = "。！？!?\n";

    private static final int MAX_INTENT_SENTENCE_LENGTH = 120;

    private ToolUseTextStripper() {
    }

    /**
     * 移除内容中的工具调用文本。无泄漏迹象时原样返回；调用片段可能出现在正文中间，
     * 只删除片段本身与其前导意图句，不影响片段前后的叙事。
     */
    public static String strip(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (!content.contains("tool_use") && !content.contains("tool_call") && !content.contains("function_call")) {
            return content;
        }

        List<int[]> spans = locateSpans(content);
        if (spans.isEmpty()) {
            return content;
        }

        StringBuilder sb = new StringBuilder(content);
        // 从后往前删，前面片段的索引不受影响
        for (int i = spans.size() - 1; i >= 0; i--) {
            int start = spans.get(i)[0];
            int end = spans.get(i)[1];
            int cutEnd = end >= 0 ? end : cutUnbalanced(sb, start);
            int cutStart = intentSentenceStart(sb, start);
            sb.delete(cutStart, cutEnd);
            log.info("已剥离工具调用文本: [{}, {}) 共 {} 字符", cutStart, cutEnd, cutEnd - cutStart);
        }

        return sb.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** 定位所有调用片段：{start, endAfterClosingParen}；括号不配平时 end 为 -1。 */
    private static List<int[]> locateSpans(String content) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = SPAN_START.matcher(content);
        while (m.find()) {
            spans.add(new int[]{m.start(), balancedEnd(content, m.end() - 1)});
        }
        return spans;
    }

    /** 从 '(' 起做括号配平，字符串内的括号与转义不参与计数；返回 ')' 之后的位置，不配平返回 -1。 */
    private static int balancedEnd(String s, int openParen) {
        int depth = 0;
        boolean inString = false;
        for (int i = openParen; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /** 括号不配平时的兜底切除范围：从片段起点删到下一个空行（段落边界）或文末。 */
    private static int cutUnbalanced(StringBuilder sb, int start) {
        int nextBlank = sb.indexOf("\n\n", start);
        log.warn("工具调用文本括号不配平，切除到最近的段落边界");
        return nextBlank >= 0 ? nextBlank : sb.length();
    }

    /**
     * 从片段起点向前找应一并删除的前导意图句起点。
     * 跨过片段前的空白后定位上一句子边界；边界句若短且符合"动作词+对象词"特征则一并删除。
     */
    private static int intentSentenceStart(StringBuilder sb, int spanStart) {
        int pos = spanStart;
        while (pos > 0 && Character.isWhitespace(sb.charAt(pos - 1))) {
            pos--;
        }
        if (pos == 0) {
            return 0;
        }
        int scanFrom = pos;
        if (SENTENCE_ENDS.indexOf(sb.charAt(scanFrom - 1)) >= 0) {
            scanFrom--;
        }
        int boundary = 0;
        for (int i = scanFrom - 1; i >= 0; i--) {
            if (SENTENCE_ENDS.indexOf(sb.charAt(i)) >= 0) {
                boundary = i + 1;
                break;
            }
        }
        String sentence = sb.substring(boundary, pos).trim();
        if (!sentence.isEmpty() && sentence.length() <= MAX_INTENT_SENTENCE_LENGTH
                && INTENT_HINT.matcher(sentence).find()) {
            return boundary;
        }
        return pos;
    }
}
