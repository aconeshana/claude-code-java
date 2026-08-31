package com.claudecode.services.statusline;

import com.claudecode.services.config.SettingsSources;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class StatusLineConfigTest {

    @TempDir Path tmp;

    @AfterEach
    void clearFlagSettings() {
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true,
            System.getProperty("user.dir"));
    }

    private static JsonNode node(String json) throws Exception {
        return JsonUtils.getMapper().readTree(json);
    }

    @Test
    void parse_validCommandConfig() throws Exception {
        Optional<StatusLineConfig> c = StatusLineConfig.parse(
            node("{\"type\":\"command\",\"command\":\"echo hi\",\"padding\":2}"));
        assertTrue(c.isPresent());
        assertEquals("echo hi", c.get().command());
        assertEquals(2, c.get().padding());
    }

    @Test
    void parse_defaultsPaddingToZero() throws Exception {
        Optional<StatusLineConfig> c = StatusLineConfig.parse(
            node("{\"type\":\"command\",\"command\":\"echo hi\"}"));
        assertTrue(c.isPresent());
        assertEquals(0, c.get().padding());
    }

    @Test
    void parse_rejectsNonCommandType() throws Exception {

        assertTrue(StatusLineConfig.parse(
            node("{\"type\":\"static\",\"command\":\"echo hi\"}")).isEmpty());
    }

    @Test
    void parse_rejectsMissingOrBlankCommand() throws Exception {
        assertTrue(StatusLineConfig.parse(node("{\"type\":\"command\"}")).isEmpty());
        assertTrue(StatusLineConfig.parse(
            node("{\"type\":\"command\",\"command\":\"  \"}")).isEmpty());
    }

    @Test
    void parse_nullAndNonObject() throws Exception {
        assertTrue(StatusLineConfig.parse(null).isEmpty());
        assertTrue(StatusLineConfig.parse(node("\"nope\"")).isEmpty());
    }

    @Test
    void parse_clampsNegativePadding() throws Exception {
        Optional<StatusLineConfig> c = StatusLineConfig.parse(
            node("{\"type\":\"command\",\"command\":\"x\",\"padding\":-5}"));
        assertTrue(c.isPresent());
        assertEquals(0, c.get().padding());
    }

    @Test
    void load_laterTierWins() throws Exception {
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Files.writeString(user, "{\"statusLine\":{\"type\":\"command\",\"command\":\"USER\"}}");
        Files.writeString(project, "{\"statusLine\":{\"type\":\"command\",\"command\":\"PROJECT\"}}");

        Optional<StatusLineConfig> c = StatusLineConfig.load(List.of(user, project));
        assertTrue(c.isPresent());
        assertEquals("PROJECT", c.get().command());  // later tier wins
    }

    @Test
    void load_skipsMalformedTierKeepsEarlierValid() throws Exception {
        Path user = tmp.resolve("user.json");
        Path project = tmp.resolve("project.json");
        Files.writeString(user, "{\"statusLine\":{\"type\":\"command\",\"command\":\"USER\"}}");
        Files.writeString(project, "{ this is not json ");

        // A broken project file must not blank out the user-level status line.
        Optional<StatusLineConfig> c = StatusLineConfig.load(List.of(user, project));
        assertTrue(c.isPresent());
        assertEquals("USER", c.get().command());
    }

    @Test
    void load_absentEverywhere() throws Exception {
        Path a = tmp.resolve("a.json");
        Files.writeString(a, "{}");
        assertTrue(StatusLineConfig.load(List.of(a, tmp.resolve("missing.json"))).isEmpty());
    }

    @Test
    void runtimeLoadIncludesFlagSettings() throws Exception {
        Path flag = tmp.resolve("flag.json");
        Files.writeString(flag,
            "{\"statusLine\":{\"type\":\"command\",\"command\":\"FLAG\"}}");
        SettingsSources.configureAllowedSettingSources(false, false, false, tmp.toString());
        SettingsSources.setFlagSettingsPath(flag);

        Optional<StatusLineConfig> c = StatusLineConfig.load(tmp.toString());
        assertTrue(c.isPresent());
        assertEquals("FLAG", c.get().command());
    }

    @Test
    void nonManagedDisableAllHooksDoesNotRunUserStatusLineWithoutPolicy() throws Exception {
        SettingsSources.configureAllowedSettingSources(false, false, false, tmp.toString());
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree("""
            {"disableAllHooks":true,
             "statusLine":{"type":"command","command":"FLAG"}}
            """));

        assertTrue(StatusLineConfig.load(tmp.toString()).isEmpty());
    }
}
