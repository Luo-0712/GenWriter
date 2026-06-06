package com.example.genwriter.agent.profile;

public record RenderedAgentPrompt(
        String systemPrompt,
        String userPrompt,
        String outputFormatDescription
) {
}
