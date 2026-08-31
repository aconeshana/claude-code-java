package com.claudecode.core.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaudePathsTest {

    @Test
    void workflowDirectoryLivesUnderClaudeHome() {
        assertEquals(ClaudePaths.WORKFLOWS_DIR, ClaudePaths.CLAUDE_HOME.resolve("workflows"));
    }

    @Test
    void configDirOverrideReplacesDefaultClaudeHome() {
        assertEquals(Path.of("/custom/config"),
            ClaudePaths.resolveClaudeHome("/custom/config", Path.of("/home/alice")));
    }

    @Test
    void globalConfigLivesInsideOverrideButNextToDefaultClaudeHome() {
        assertEquals(Path.of("/custom/config/.claude.json"),
            ClaudePaths.resolveGlobalJson("/custom/config", Path.of("/home/alice"), _ -> false));
        assertEquals(Path.of("/home/alice/.claude.json"),
            ClaudePaths.resolveGlobalJson(null, Path.of("/home/alice"), _ -> false));
    }

    @Test
    void hiddenLegacyConfigTakesPrecedenceWhenItExists() {
        Path legacy = Path.of("/custom/config/.config.json");
        assertEquals(legacy,
            ClaudePaths.resolveGlobalJson("/custom/config", Path.of("/home/alice"), legacy::equals));
    }
}
