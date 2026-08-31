package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsFlagSourceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearFlagSource() {
        SettingsSources.clearFlagSettings();
    }

    @Test
    void keepsTheBackingPathAndMergesSdkOverlayWithFileSettings() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"nested\":{\"fromFile\":true},"
            + "\"values\":[\"file\"],\"permissions\":{\"allow\":[\"Read(*)\"]}}");
        ObjectNode file = (ObjectNode) JsonUtils.readJson(source);
        SettingsSources.setFlagSettingsSource(source, file);

        ObjectNode incoming = JsonUtils.getMapper().createObjectNode();
        incoming.putObject("nested").put("fromSdk", true);
        incoming.putArray("values").add("sdk");
        incoming.putObject("permissions").putArray("deny").add("Bash(*)");
        SettingsSources.applyFlagSettings(incoming);

        assertEquals(source.toAbsolutePath().normalize(), SettingsSources.flagSettingsPath());
        assertEquals(source.getParent().toAbsolutePath().normalize(),
            SettingsSources.flagSettingsRootPath(tempDir.toString()));
        ObjectNode effective = SettingsSources.flagSettingsSnapshot();
        assertTrue(effective.path("nested").path("fromFile").asBoolean());
        assertTrue(effective.path("nested").path("fromSdk").asBoolean());
        assertEquals(2, effective.path("values").size());
        assertEquals(2, PermissionSettings.loadPermissionRules(tempDir.toString()).stream()
            .filter(rule -> rule.source() == RuleSource.FLAG_SETTINGS).count());
    }

    @Test
    void sdkOverlayUsesTopLevelReplacementLikeTheOriginalControlProtocol() throws Exception {
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"sandbox\":{\"enabled\":true,\"allowUnsandboxedCommands\":false}}"));
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"sandbox\":{\"enabled\":false}}"));

        ObjectNode inline = SettingsSources.flagSettingsSnapshot();
        assertFalse(inline.path("sandbox").has("allowUnsandboxedCommands"));
        assertFalse(inline.path("sandbox").path("enabled").asBoolean());
    }

    @Test
    void keepsStructurallyEqualObjectArrayEntriesFromDifferentSettingsSources() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"sshConfigs\":[{\"id\":\"dev\",\"name\":\"Dev\",\"sshHost\":\"host\"}]}" );
        SettingsSources.setFlagSettingsSource(source, JsonUtils.readJson(source));
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"sshConfigs\":[{\"id\":\"dev\",\"name\":\"Dev\",\"sshHost\":\"host\"}]}"));

        assertEquals(2, SettingsSources.flagSettingsSnapshot().path("sshConfigs").size());
    }

    @Test
    void refreshesAFileBackedFlagSourceWhenGetSettingsReadsItAgain() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"model\":\"first\"}");
        SettingsSources.setFlagSettingsPath(source);
        assertEquals("first", SettingsSources.flagSettingsSnapshot().path("model").asText());

        Files.writeString(source, "{\"model\":\"second\"}");
        assertEquals("second", SettingsSources.flagSettingsSnapshot().path("model").asText());
    }

    @Test
    void changingTheBackingPathKeepsTheExistingInlineOverlayLikeTsState() throws Exception {
        SettingsSources.clearFlagSettings();
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"language\":\"sdk-language\"}"));
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"model\":\"file-model\"}");

        SettingsSources.setFlagSettingsPath(source);

        ObjectNode snapshot = SettingsSources.flagSettingsSnapshot();
        assertEquals("file-model", snapshot.path("model").asText());
        assertEquals("sdk-language", snapshot.path("language").asText());
    }

    @Test
    void acceptsUtf8BomInFileBackedSettingsLikeTsSafeParseJson() throws Exception {
        Path source = tempDir.resolve("bom-settings.json");
        Files.write(source, ("\uFEFF{\"model\":\"bom\"}")
            .getBytes(StandardCharsets.UTF_8));
        SettingsSources.setFlagSettingsPath(source);

        assertEquals("bom", SettingsSources.flagSettingsSnapshot().path("model").asText());
    }

    @Test
    void treatsBomOnlyFlagFileAsBlankLikeTsTrim() throws Exception {
        Path source = tempDir.resolve("bom-only-settings.json");
        Files.write(source, "\uFEFF".getBytes(StandardCharsets.UTF_8));
        SettingsSources.setFlagSettingsPath(source);
        String cwd = tempDir.toString();
        SettingsSources.configureAllowedSettingSources(false, false, false, cwd);
        try {
            SettingsWithErrors result =
                SettingsDiagnostics.loadSettingsWithErrors(cwd);
            assertTrue(result.errors().isEmpty());
            assertFalse(result.settings().has("model"));
        } finally {
            SettingsSources.configureAllowedSettingSources(true, true, true, cwd);
        }
    }

    @Test
    void getSettingsWithSourcesInvalidatesTheFlagFileCacheToo() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"model\":\"first\"}");
        FileTime stamp = Files.getLastModifiedTime(source);
        SettingsSources.setFlagSettingsPath(source);
        assertEquals("first", SettingsSources.flagSettingsSnapshot().path("model").asText());

        Files.writeString(source, "{\"model\":\"second\"}");
        Files.setLastModifiedTime(source, stamp);
        SettingsSnapshots.withSources(tempDir.toString());
        assertEquals("second", SettingsSources.flagSettingsSnapshot().path("model").asText(),
            "fresh get_settings snapshots must bypass the flag file cache as well");
    }

    @Test
    void installingAPreviouslyCachedFlagPathClearsTheParsedTreeCache() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source,
            "{\"permissions\":{\"defaultMode\":\"default\"}}");
        FileTime stamp = Files.getLastModifiedTime(source);

        // Populate the ordinary path-keyed cache before this file becomes the
        // --settings source.  Keep the replacement the same size and restore
        // the timestamp so only explicit cache invalidation can distinguish it.
        assertEquals("default", PermissionSettings.loadDefaultPermissionMode(List.of(source)));
        Files.writeString(source,
            "{\"permissions\":{\"defaultMode\":\"dontAsk\"}}");
        Files.setLastModifiedTime(source, stamp);

        SettingsSources.setFlagSettingsPath(source);
        assertEquals("dontAsk", SettingsSources.flagSettingsSnapshot()
            .path("permissions").path("defaultMode").asText());
    }

    @Test
    void clearingFlagSettingsAlsoClearsThePathTreeCache() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source,
            "{\"permissions\":{\"defaultMode\":\"default\"}}");
        FileTime stamp = Files.getLastModifiedTime(source);

        SettingsSources.setFlagSettingsPath(source);
        assertEquals("default", SettingsSources.flagSettingsSnapshot()
            .path("permissions").path("defaultMode").asText());

        SettingsSources.clearFlagSettings();
        Files.writeString(source,
            "{\"permissions\":{\"defaultMode\":\"dontAsk\"}}");
        Files.setLastModifiedTime(source, stamp);

        assertEquals("dontAsk", PermissionSettings.loadDefaultPermissionMode(List.of(source)));
    }

    @Test
    void rejectsAnInvalidInlineSourceButKeepsTheValidFileSource() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"model\":\"file-model\"}");
        SettingsSources.setFlagSettingsPath(source);
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"model\":123,\"language\":\"sdk-language\"}"));

        ObjectNode snapshot = SettingsSources.flagSettingsSnapshot();
        assertEquals("file-model", snapshot.path("model").asText());
        assertFalse(snapshot.has("language"));
    }

    @Test
    void rejectsInlineInvalidPermissionRuleWithoutPartiallyApplyingOverlay() throws Exception {
        Path source = tempDir.resolve("settings.json");
        Files.writeString(source, "{\"model\":\"file-model\"}");
        SettingsSources.setFlagSettingsPath(source);
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"language\":\"sdk-language\",\"permissions\":{\"allow\":[\"not a rule\"]}}"));

        ObjectNode snapshot = SettingsSources.flagSettingsSnapshot();
        assertEquals("file-model", snapshot.path("model").asText());
        assertFalse(snapshot.has("language"));
    }

    @Test
    void nonPolicyDisableAllHooksFromFlagLeavesOnlyManagedHooksEligible() {
        String cwd = System.getProperty("user.dir");
        SettingsSources.configureAllowedSettingSources(false, false, false, cwd);
        try {
            SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
                "{\"disableAllHooks\":true,\"hooks\":{\"Stop\":[]}}"));
            assertTrue(HookSettings.loadHooksSettings().eventHooks().isEmpty());
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            SettingsSources.configureAllowedSettingSources(true, true, true, cwd);
        }
    }
}
