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

    public static String outlineConstraint(String userInput) {
        return WritingPromptConstraints.outlineConstraint(WritingGenreResolver.resolve(userInput));
    }

    public static String draftConstraint(String userInput) {
        return WritingPromptConstraints.draftConstraint(WritingGenreResolver.resolve(userInput));
    }

    public static String reviewConstraint(String userInput) {
        return WritingPromptConstraints.reviewConstraint(WritingGenreResolver.resolve(userInput), true);
    }
}
