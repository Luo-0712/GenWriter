package com.example.genwriter.agent.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAll_ShouldLoadPackagedMarkdownProfiles() {
        AgentProfileLoader loader = new AgentProfileLoader(tempDir);

        loader.loadAll();

        AgentProfileDefinition draft = loader.get("draft");
        assertNotNull(draft);
        assertEquals("正文写作", draft.displayName());
        assertTrue(draft.systemPrompt().contains("内部蓝图"));
        assertTrue(draft.genreGuards().contains("direct narrative prose"));
    }

    @Test
    void loadAll_ShouldLetExternalProfileOverridePackagedProfile() throws Exception {
        Files.writeString(tempDir.resolve("draft.md"), """
                ---
                name: draft
                agent: draft
                displayName: 覆盖正文
                version: "9.9.9"
                ---

                ## Role
                external role

                ## System Prompt
                external system

                ## User Prompt Template
                external {{userInput}}

                ## Output Contract
                external output

                ## Genre Guards
                external guards
                """);
        AgentProfileLoader loader = new AgentProfileLoader(tempDir);

        loader.loadAll();

        AgentProfileDefinition draft = loader.get("draft");
        assertNotNull(draft);
        assertEquals("覆盖正文", draft.displayName());
        assertEquals("external system", draft.systemPrompt());
    }
}
