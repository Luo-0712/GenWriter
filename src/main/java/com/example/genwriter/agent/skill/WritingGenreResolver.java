package com.example.genwriter.agent.skill;

import java.util.Locale;

public final class WritingGenreResolver {

    private WritingGenreResolver() {
    }

    public static WritingGenreProfile resolve(String userInput) {
        String text = normalize(userInput);
        if (text.isBlank()) {
            return WritingGenreProfile.of(WritingGenre.UNKNOWN);
        }
        if (containsAny(text, "技术文档", "接口文档", "api 文档", "api文档", "设计文档", "操作手册",
                "tech doc", "technical document", "developer guide")) {
            return WritingGenreProfile.of(WritingGenre.TECH_DOC);
        }
        if (containsAny(text, "调研报告", "分析报告", "研究报告", "总结报告", "报告", "report")) {
            return WritingGenreProfile.of(WritingGenre.REPORT);
        }
        if (containsAny(text, "学术论文", "论文", "文献综述", "摘要", "academic", "paper", "thesis")) {
            return WritingGenreProfile.of(WritingGenre.ACADEMIC);
        }
        if (containsAny(text, "小说", "章节", "续写", "下一章", "科幻", "故事", "剧情", "角色",
                "chapter", "novel", "sci-fi", "science fiction", "story")) {
            return WritingGenreProfile.of(WritingGenre.NOVEL);
        }
        if (containsAny(text, "诗歌", "诗", "poem", "poetry")) {
            return WritingGenreProfile.of(WritingGenre.POETRY);
        }
        if (containsAny(text, "文章", "短文", "公众号", "推文", "article", "essay")) {
            return WritingGenreProfile.of(WritingGenre.ARTICLE);
        }
        return WritingGenreProfile.of(WritingGenre.UNKNOWN);
    }

    public static boolean isNovelRequest(String userInput) {
        return resolve(userInput).isNovel();
    }

    public static boolean isNovelCreationRequest(String userInput) {
        if (!isNovelRequest(userInput)) {
            return false;
        }
        String text = normalize(userInput);
        return containsAny(text,
                "写", "创作", "生成", "撰写", "起草", "来一", "给我", "我要",
                "续写", "继续", "下一章", "后续", "两章", "三章", "两三章", "几章",
                "write", "create", "draft", "generate", "continue", "next chapter");
    }

    public static boolean isContinueRequest(String userInput) {
        String text = normalize(userInput);
        return containsAny(text,
                "续写", "继续", "下一章", "后续", "接着", "承接",
                "continue", "next chapter", "chapter 2", "chapter two");
    }

    public static String forcedWritingType(String userInput) {
        if (!isNovelCreationRequest(userInput)) {
            return null;
        }
        return isContinueRequest(userInput) ? "CONTINUE" : "CREATE";
    }

    static WritingGenreProfile profileFromContext(Object writingGenre, String userInput) {
        if (writingGenre instanceof WritingGenre genre) {
            return WritingGenreProfile.of(genre);
        }
        if (writingGenre instanceof String value && !value.isBlank()) {
            try {
                return WritingGenreProfile.of(WritingGenre.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return resolve(userInput);
            }
        }
        return resolve(userInput);
    }

    private static String normalize(String userInput) {
        return userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
