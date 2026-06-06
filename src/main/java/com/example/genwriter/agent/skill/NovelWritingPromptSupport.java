package com.example.genwriter.agent.skill;

public final class NovelWritingPromptSupport {

    private NovelWritingPromptSupport() {
    }

    public static boolean isNovelRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String text = userInput.toLowerCase();
        return text.contains("小说")
                || text.contains("章节")
                || text.contains("续写")
                || text.contains("科幻")
                || text.contains("科技文")
                || text.contains("chapter")
                || text.contains("novel")
                || text.contains("sci-fi")
                || text.contains("science fiction");
    }

    public static boolean isNovelCreationRequest(String userInput) {
        if (!isNovelRequest(userInput)) {
            return false;
        }
        String text = userInput.toLowerCase();
        return containsAny(text,
                "写", "创作", "生成", "撰写", "起草", "来一", "给我", "我要",
                "续写", "继续", "下一章", "后续", "两章", "三章", "两三章", "几章",
                "write", "create", "draft", "generate", "continue", "next chapter");
    }

    public static boolean isContinueRequest(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return false;
        }
        String text = userInput.toLowerCase();
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

    public static String outlineConstraint(String userInput) {
        if (!isNovelRequest(userInput)) {
            return "";
        }
        return """

                ## Novel chapter planning constraints
                The user is asking for novel/chapter creation. Plan for direct narrative fiction, not an essay,
                report, setting document, or analysis. Keep the outline internal and executable:
                chapter title, scene progression, character action, conflict, dialogue beats, and continuity hooks.
                For science-fiction or technology-themed requests, keep hard-technology causality clear, but make
                every technical detail serve story action.
                """;
    }

    public static String draftConstraint(String userInput) {
        if (!isNovelRequest(userInput)) {
            return "";
        }
        return """

                ## Novel chapter output constraints
                Write the final answer as direct narrative prose. Include chapter title, scene movement, character
                action, concrete sensory details, and dialogue where natural. Do not output an outline, report,
                proposal, analysis, explanation, or setting sheet. For science-fiction or technology-themed
                requests, present technology through conflict, consequences, and character choices instead of
                expository lists.
                """;
    }

    public static String reviewConstraint(String userInput) {
        if (!isNovelRequest(userInput)) {
            return "";
        }
        return """

                ## Novel shape gate
                The user requested novel/chapter prose. If the submitted content reads like an outline, report,
                analysis, commentary, plan, or setting sheet rather than narrative fiction, set verdict to
                "REVISE_DRAFT" and explain that the draft must be rewritten as direct chapter prose.
                """;
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
