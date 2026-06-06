package com.example.genwriter.agent.skill;

public final class NovelWritingPromptSupport {

    private NovelWritingPromptSupport() {
    }

    public static boolean isNovelRequest(String userInput) {
        return WritingGenreResolver.isNovelRequest(userInput);
    }

    public static boolean isNovelCreationRequest(String userInput) {
        return WritingGenreResolver.isNovelCreationRequest(userInput);
    }

    public static boolean isContinueRequest(String userInput) {
        return WritingGenreResolver.isContinueRequest(userInput);
    }

    public static String forcedWritingType(String userInput) {
        return WritingGenreResolver.forcedWritingType(userInput);
    }
}
