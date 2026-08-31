package com.claudecode.tools.agent;

import org.apache.commons.lang3.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentMemoryPromptTest {

    @Test
    void buildIncludesScopeGuidanceAndLiveEntrypoint(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("MEMORY.md"), "- Prefer integration tests");

        String prompt = AgentMemoryPrompt.build(tempDir, "project");

        assertTrue(Strings.CS.startsWith(prompt, "# Persistent Agent Memory"), prompt);
        assertTrue(Strings.CS.contains(prompt, tempDir.toString()), prompt);
        assertTrue(Strings.CS.contains(prompt, "project-scope and shared with your team via version control"), prompt);
        assertTrue(Strings.CS.contains(prompt, "- Prefer integration tests"), prompt);
    }
}
