package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.Strings;

/** Regression tests for CLI LSP configuration provenance. */
class CliLspIntegrationTest {

    @Test
    void bootstrapReadsPluginSnapshotOnly() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/com/claudecode/cli/CliLspIntegration.java"));

        assertFalse(Strings.CS.contains(source, "SettingsSnapshots"));
        assertFalse(Strings.CS.contains(source, ".path(\"effective\")"));
        assertTrue(Strings.CS.contains(source, "snapshot.lspServers()"));
        assertFalse(Strings.CS.contains(source, "LspServerSettings.load()"));
        assertFalse(Strings.CS.contains(source, "new PluginRuntimeLoader"));
        assertFalse(Strings.CS.contains(source, "List<Path> inlinePluginDirs"));
        assertFalse(Strings.CS.contains(source, "private QuerySession engine"));
    }
}
