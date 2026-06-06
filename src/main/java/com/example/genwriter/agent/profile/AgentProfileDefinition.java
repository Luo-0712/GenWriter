package com.example.genwriter.agent.profile;

import java.util.Map;

public record AgentProfileDefinition(
        String name,
        String agent,
        String displayName,
        String description,
        String version,
        String role,
        String systemPrompt,
        String userPromptTemplate,
        String outputContract,
        String genreGuards,
        String source,
        Map<String, Object> metadata
) {
}
