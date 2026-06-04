package com.example.genwriter.agent.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared tool registration names and per-request visibility helpers.
 */
public final class AgentToolSupport {

    public static final String WEB_SEARCH = "web_search";
    public static final String KNOWLEDGE_BASE_SEARCH = "knowledge_base_search";
    public static final String WEB_SEARCH_ENABLED = "webSearchEnabled";

    private AgentToolSupport() {
    }

    public static ChatClient.ChatClientRequestSpec applyToolVisibility(
            ChatClient.ChatClientRequestSpec promptSpec,
            boolean webSearchEnabled,
            boolean knowledgeBaseSearchVisible) {

        List<String> toolNames = new ArrayList<>();
        if (webSearchEnabled) {
            toolNames.add(WEB_SEARCH);
        }
        if (knowledgeBaseSearchVisible) {
            toolNames.add(KNOWLEDGE_BASE_SEARCH);
        }
        if (!toolNames.isEmpty()) {
            promptSpec = promptSpec.tools(toolNames.toArray(new String[0]));
        }
        return promptSpec.toolContext(Map.of(WEB_SEARCH_ENABLED, webSearchEnabled));
    }

    public static boolean isWebSearchEnabled(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return true;
        }
        return isWebSearchEnabled(toolContext.getContext().get(WEB_SEARCH_ENABLED));
    }

    public static boolean isWebSearchEnabled(Object enabled) {
        return enabled == null || !"false".equalsIgnoreCase(String.valueOf(enabled));
    }

    public static String appendWebSearchDisabledNotice(String prompt, boolean webSearchEnabled) {
        if (webSearchEnabled) {
            return prompt;
        }
        String basePrompt = prompt != null ? prompt : "";
        return basePrompt + """

                ## Tool constraints
                web_search is disabled for this request. Do not call web_search; use available knowledge base results, attachments, memory, and existing context only.
                """;
    }
}
