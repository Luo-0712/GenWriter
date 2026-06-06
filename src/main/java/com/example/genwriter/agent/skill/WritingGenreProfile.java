package com.example.genwriter.agent.skill;

public record WritingGenreProfile(WritingGenre genre, String displayName) {

    public static WritingGenreProfile of(WritingGenre genre) {
        WritingGenre safeGenre = genre != null ? genre : WritingGenre.UNKNOWN;
        return new WritingGenreProfile(safeGenre, safeGenre.displayName());
    }

    public boolean isNovel() {
        return genre == WritingGenre.NOVEL;
    }

    public boolean isStructuredArticleLike() {
        return genre == WritingGenre.ARTICLE
                || genre == WritingGenre.ACADEMIC
                || genre == WritingGenre.TECH_DOC
                || genre == WritingGenre.REPORT;
    }
}
