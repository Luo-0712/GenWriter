package com.example.genwriter.agent.tool;

import java.util.List;

/**
 * 网页搜索工具接口
 * 支持多种搜索引擎实现（Tavily、SerpAPI、Bing等）
 */
public interface WebSearchTool {

    /**
     * 执行网页搜索
     *
     * @param query 搜索查询语句
     * @param topK  返回结果数量上限
     * @return 搜索结果列表
     */
    List<WebSearchResult> search(String query, int topK);
}
