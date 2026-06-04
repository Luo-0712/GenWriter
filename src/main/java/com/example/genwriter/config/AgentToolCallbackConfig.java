package com.example.genwriter.config;

import com.example.genwriter.agent.tool.AgentToolSupport;
import com.example.genwriter.agent.tool.KnowledgeBaseTool;
import com.example.genwriter.agent.tool.TavilyWebSearchTool;
import com.example.genwriter.agent.tool.ToolResult;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.BiFunction;
import java.util.function.Function;

@Configuration
public class AgentToolCallbackConfig {

    @Bean
    public FunctionCallback webSearchToolCallback(TavilyWebSearchTool webSearchTool) {
        BiFunction<TavilyWebSearchTool.WebSearchInput, ToolContext, String> gatedWebSearch =
                (input, toolContext) -> {
                    if (!AgentToolSupport.isWebSearchEnabled(toolContext)) {
                        return ToolResult.fail("web_search is disabled for this request. Continue without web search.").toJson();
                    }
                    return webSearchTool.apply(input);
                };

        return FunctionToolCallback
                .builder(AgentToolSupport.WEB_SEARCH, gatedWebSearch)
                .description("Search the web for current information, facts, data, or internet content.")
                .inputType(TavilyWebSearchTool.WebSearchInput.class)
                .build();
    }

    @Bean
    public FunctionCallback knowledgeBaseSearchToolCallback(KnowledgeBaseTool knowledgeBaseTool) {
        return FunctionToolCallback
                .builder(AgentToolSupport.KNOWLEDGE_BASE_SEARCH,
                        (Function<KnowledgeBaseTool.KnowledgeSearchInput, String>) knowledgeBaseTool)
                .description("Search the knowledge base for relevant content. Use this when a knowledge base ID (kbId) is provided and the question relates to knowledge base content.")
                .inputType(KnowledgeBaseTool.KnowledgeSearchInput.class)
                .build();
    }
}
