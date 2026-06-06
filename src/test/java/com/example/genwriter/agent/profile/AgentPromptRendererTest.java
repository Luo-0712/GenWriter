package com.example.genwriter.agent.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPromptRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void render_ShouldReplaceVariablesAndConditionals() throws Exception {
        Files.writeString(tempDir.resolve("sample.md"), """
                ---
                name: sample
                agent: sample
                displayName: Sample
                version: "1.0.0"
                ---

                ## Role
                role {{writingGenre}}

                ## System Prompt
                system {{missing}}

                ## User Prompt Template
                {{#if context}}
                Context: {{context}}
                {{/if}}
                User: {{userInput}}

                ## Output Contract
                output

                ## Genre Guards
                guards
                """);
        AgentProfileLoader loader = new AgentProfileLoader(tempDir);
        loader.loadAll();
        AgentPromptRenderer renderer = new AgentPromptRenderer(loader);

        RenderedAgentPrompt rendered = renderer.render("sample", Map.of(
                "writingGenre", "NOVEL",
                "context", "ctx",
                "userInput", "hello"
        ));

        assertTrue(rendered.systemPrompt().contains("role NOVEL"));
        assertTrue(rendered.systemPrompt().contains("system"));
        assertTrue(rendered.userPrompt().contains("Context: ctx"));
        assertTrue(rendered.userPrompt().contains("User: hello"));
    }

    @Test
    void render_ShouldDropFalseConditionBlocksAndKeepMarkdownText() {
        AgentProfileLoader loader = new AgentProfileLoader(tempDir);
        AgentPromptRenderer renderer = new AgentPromptRenderer(loader);

        String rendered = renderer.renderTemplate("""
                {{#if outline}}
                ## Outline
                {{outline}}
                {{/if}}
                **Text** {{missing}}
                """, Map.of());

        assertFalse(rendered.contains("Outline"));
        assertTrue(rendered.contains("**Text**"));
    }

    @Test
    void renderDraft_ShouldContainNarrativeGuardFromProfile() {
        AgentProfileLoader loader = new AgentProfileLoader(tempDir);
        loader.loadAll();
        AgentPromptRenderer renderer = new AgentPromptRenderer(loader);

        RenderedAgentPrompt rendered = renderer.render("draft", Map.of(
                "outline", "核心情境：雨夜旧公寓",
                "context", "",
                "userInput", "写一篇短篇小说",
                "reviewFeedback", "",
                "writingGenre", "NOVEL",
                "markdownEnabled", true
        ));

        assertTrue(rendered.systemPrompt().contains("direct narrative prose"));
        assertTrue(rendered.systemPrompt().contains("禁止复制内部蓝图的标题"));
        assertTrue(rendered.userPrompt().contains("内部蓝图"));
    }
}
