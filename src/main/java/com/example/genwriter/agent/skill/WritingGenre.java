package com.example.genwriter.agent.skill;

public enum WritingGenre {
    NOVEL("小说"),
    ARTICLE("文章"),
    ACADEMIC("学术论文"),
    TECH_DOC("技术文档"),
    REPORT("报告"),
    POETRY("诗歌"),
    UNKNOWN("通用写作");

    private final String displayName;

    WritingGenre(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
