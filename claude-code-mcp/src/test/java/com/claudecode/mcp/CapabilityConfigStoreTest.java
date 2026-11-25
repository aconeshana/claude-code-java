package com.claudecode.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CapabilityConfigStoreTest {

    @Test
    void persistsAndReloadsProjectCapabilityDefaultsWithoutClobberingSiblingFields(
            @TempDir Path tempDir) throws Exception {
        Path projectDir = tempDir.resolve("my-project");
        Files.createDirectories(projectDir);
        Path globalJson = tempDir.resolve(".claude.json");
        Files.writeString(globalJson, """
            {
              "numStartups": 42,
              "projects": {}
            }
            """);

        assertTrue(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_TOOLS, "WebSearch", true, globalJson));
        assertFalse(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_TOOLS, "WebSearch", true, globalJson));
        assertTrue(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_SKILLS, "deploy", true, globalJson));
        assertTrue(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_MCP_SERVERS, "github", true, globalJson));
        assertTrue(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_MCP_TOOLS, "mcp__github__create_issue",
            true, globalJson));

        var loaded = CapabilityConfigStore.loadProjectDefaults(projectDir, globalJson);
        assertEquals(Set.of("WebSearch"), loaded.disabledTools());
        assertEquals(Set.of("deploy"), loaded.disabledSkills());
        assertEquals(Set.of("github"), loaded.disabledMcpServers());
        assertEquals(Set.of("mcp__github__create_issue"), loaded.disabledMcpTools());
        assertEquals(42, JsonUtils.readJson(globalJson).path("numStartups").asInt());

        // Re-enabling removes the entry cleanly.
        assertTrue(CapabilityConfigStore.setDisabledEntry(
            projectDir, CapabilityConfigStore.FIELD_DISABLED_SKILLS, "deploy", false, globalJson));
        assertTrue(CapabilityConfigStore.loadProjectDefaults(projectDir, globalJson)
            .disabledSkills().isEmpty());

        // Kernel tools cannot be persisted as disabled.
        assertThrows(IllegalArgumentException.class, () ->
            CapabilityConfigStore.setDisabledEntry(
                projectDir, CapabilityConfigStore.FIELD_DISABLED_TOOLS, "ToolInvoke", true, globalJson));
    }
}
