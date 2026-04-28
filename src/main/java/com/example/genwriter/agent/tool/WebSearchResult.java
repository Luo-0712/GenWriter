package com.example.genwriter.agent.tool;

/**
 * 网页搜索结果
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String content,
        String source
) {
}
