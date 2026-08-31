package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.CwdState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SettingsDecompositionContractTest {

    @TempDir
    Path tempDir;

    private final String originalCwd = System.getProperty("user.dir");
    private final Path originalSessionCwd = CwdState.getOriginalCwd();

    @AfterEach
    void restoreProcessLocalSettingsState() {
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        if (originalSessionCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(originalSessionCwd);
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalCwd == null ? tempDir.toString() : originalCwd);
    }

    @Test
    void pathHelpersNormalizeProjectAndLocalSettingsBeneathTheSuppliedCwd() {
        Path cwd = tempDir.resolve("workspace/../project");
        Path normalizedCwd = cwd.toAbsolutePath().normalize();

        assertEquals(normalizedCwd.resolve(".claude/settings.json"),
            SettingsPaths.projectSettingsPath(cwd.toString()));
        assertEquals(normalizedCwd.resolve(".claude/settings.local.json"),
            SettingsPaths.localSettingsPath(cwd.toString()));
    }

    @Test
    void explicitSourceSelectionAppendsPolicyThenFlagInAnImmutableOrder() {
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS), tempDir.toString(), false);

        List<RuleSource> order = SettingsSources.enabledOrder();
        assertEquals(List.of(
            RuleSource.USER_SETTINGS,
            RuleSource.POLICY_SETTINGS,
            RuleSource.FLAG_SETTINGS), order);
        assertTrue(SettingsSources.isEnabled(RuleSource.POLICY_SETTINGS));
        assertTrue(SettingsSources.isEnabled(RuleSource.FLAG_SETTINGS));
        assertThrows(UnsupportedOperationException.class,
            () -> order.add(RuleSource.LOCAL_SETTINGS));
    }

    @Test
    void pathInjectedSnapshotLetsLaterFlagSettingsOverridePolicySettings() throws Exception {
        Path policy = tempDir.resolve("managed-settings.json");
        Path flag = tempDir.resolve("flag-settings.json");
        Files.writeString(policy, "{\"model\":\"managed-model\"}");
        Files.writeString(flag, "{\"model\":\"flag-model\"}");

        ObjectNode snapshot = SettingsSnapshots.withSources(List.of(
            Map.entry("policySettings", policy),
            Map.entry("flagSettings", flag)));

        assertEquals("flag-model", snapshot.path("effective").path("model").asText());
        assertEquals(List.of("policySettings", "flagSettings"), sourceNames(snapshot));
        assertEquals("managed-model", sourceSettings(snapshot, "policySettings")
            .path("model").asText());
        assertEquals("flag-model", sourceSettings(snapshot, "flagSettings")
            .path("model").asText());
    }

    @Test
    void nullSdkFlagValueDeletesOnlyTheOverlayAndRevealsTheFileValue() throws Exception {
        SettingsSources.configureAllowedSettingSources(List.of(), tempDir.toString(), false);
        Path flag = tempDir.resolve("flag-settings.json");
        Files.writeString(flag, "{\"model\":\"file-model\",\"language\":\"file-language\"}");
        SettingsSources.setFlagSettingsPath(flag);

        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"model\":\"sdk-model\",\"language\":\"sdk-language\"}"));
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree("{\"model\":null}"));

        ObjectNode snapshot = SettingsSnapshots.withSources(tempDir.toString());
        JsonNode flagSettings = sourceSettings(snapshot, "flagSettings");
        assertEquals("file-model", snapshot.path("effective").path("model").asText());
        assertEquals("sdk-language", snapshot.path("effective").path("language").asText());
        assertEquals("file-model", flagSettings.path("model").asText());
        assertEquals("sdk-language", flagSettings.path("language").asText());
    }

    @Test
    void extractedSettingsFacadeSharesOneFlagSettingsState() throws Exception {
        Path flag = tempDir.resolve("flag-settings.json");
        Files.writeString(flag, "{\"model\":\"file-model\"}");

        // The CLI and runtime readers (hooks/workspace/status) must observe one flag source.
        SettingsSources.setFlagSettingsPath(flag);
        assertEquals("file-model", SettingsSources.flagSettingsSnapshot()
            .path("model").asText());
        assertEquals("file-model", SettingsSources.flagSettingsSnapshot()
            .path("model").asText());

        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"language\":\"sdk-language\"}"));
        assertEquals("sdk-language", SettingsSources.flagSettingsSnapshot()
            .path("language").asText());
        assertEquals("sdk-language", SettingsSnapshots.effective(tempDir.toString())
            .path("language").asText());
    }

    @Test
    void snapshotRefreshAlsoInvalidatesExtractedTreeCache() throws Exception {
        String cwd = tempDir.toString();
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.PROJECT_SETTINGS), cwd, false);
        Path settings = SettingsPaths.projectSettingsPath(cwd);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"model\":\"one\"}");
        assertEquals("one", SettingsSnapshots.effective(cwd).path("model").asText());

        FileTime stamp = Files.getLastModifiedTime(settings);
        Files.writeString(settings, "{\"model\":\"two\"}");
        Files.setLastModifiedTime(settings, stamp);

        // The fresh SDK snapshot read must invalidate the cache used by
        // hooks/workspace/runtime callers as well.
        SettingsSnapshots.withSources(cwd);
        assertEquals("two", SettingsSnapshots.effective(cwd).path("model").asText());
    }

    @Test
    void effectiveSettingsUseOriginalSessionRootAfterLiveCwdChanges() throws Exception {
        Path sessionRoot = tempDir.resolve("session-root");
        Path nestedCwd = sessionRoot.resolve("nested");
        Path sessionSettings = SettingsPaths.projectSettingsPath(sessionRoot.toString());
        Path nestedSettings = SettingsPaths.projectSettingsPath(nestedCwd.toString());
        Files.createDirectories(sessionSettings.getParent());
        Files.createDirectories(nestedSettings.getParent());
        Files.writeString(sessionSettings, "{\"model\":\"session-model\"}");
        Files.writeString(nestedSettings, "{\"model\":\"nested-model\"}");

        CwdState.setOriginalCwd(sessionRoot);
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.PROJECT_SETTINGS), nestedCwd.toString(), false);

        assertEquals("session-model",
            SettingsSnapshots.effective(nestedCwd.toString()).path("model").asText());
    }

    @Test
    void disabledEditableSourceFollowsWorktreeRootWithoutReconfiguringSourceNames() {
        Path firstRoot = tempDir.resolve("first-root");
        Path secondRoot = tempDir.resolve("second-root");
        CwdState.setOriginalCwd(firstRoot);
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS), firstRoot.toString(), false);

        CwdState.setOriginalCwd(secondRoot);

        assertTrue(SettingsSources.isReadPathDisabled(
            SettingsPaths.projectSettingsPath(secondRoot.toString())));
        assertTrue(SettingsSources.isReadPathDisabled(
            SettingsPaths.localSettingsPath(secondRoot.toString())));
    }

    @Test
    void aMovedEnabledSourceCanAliasTheUserPathWithoutBeingBlockedByAStaleDenyEntry() {
        Path firstRoot = tempDir.resolve("first-root");
        CwdState.setOriginalCwd(firstRoot);
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.PROJECT_SETTINGS), firstRoot.toString(), false);

        // The first configuration disables the user path. Moving the session root to the
        // parent of ~/.claude makes projectSettings resolve to that same path; the enabled

        Path userSettings = SettingsPaths.userSettingsPath().toAbsolutePath().normalize();
        CwdState.setOriginalCwd(ClaudePaths.currentClaudeHome().getParent());

        assertEquals(userSettings,
            SettingsPaths.sessionProjectSettingsPath(firstRoot.toString()));
        assertFalse(SettingsSources.isReadPathDisabled(userSettings));
    }

    @Test
    void replacingAndClearingPluginBaseChangesLaterDetachedSnapshots() throws Exception {
        SettingsSources.configureAllowedSettingSources(List.of(), tempDir.toString(), false);
        SettingsSources.setPluginSettingsBase(JsonUtils.getMapper().readTree(
            "{\"agent\":\"plugin-agent\"}"));

        ObjectNode firstSnapshot = SettingsSnapshots.withSources(tempDir.toString());
        assertEquals("plugin-agent", firstSnapshot.path("effective").path("agent").asText());
        ((ObjectNode) firstSnapshot.path("effective")).put("agent", "caller-mutated");

        ObjectNode secondSnapshot = SettingsSnapshots.withSources(tempDir.toString());
        assertEquals("plugin-agent", secondSnapshot.path("effective").path("agent").asText(),
            "a caller-mutated snapshot must not mutate the retained plugin base");

        SettingsSources.clearPluginSettingsBase();
        ObjectNode afterClear = SettingsSnapshots.withSources(tempDir.toString());
        assertNotEquals("plugin-agent", afterClear.path("effective").path("agent").asText(),
            "clearing plugin settings must invalidate any retained effective snapshot");
        assertFalse(sourceNames(afterClear).contains("pluginSettings"),
            "plugin settings are a base layer, not a user-visible file source");
    }

    private static List<String> sourceNames(ObjectNode snapshot) {
        return StreamSupport.stream(snapshot.path("sources").spliterator(), false)
            .map(source -> source.path("source").asText())
            .toList();
    }

    private static JsonNode sourceSettings(ObjectNode snapshot, String sourceName) {
        return StreamSupport.stream(snapshot.path("sources").spliterator(), false)
            .filter(source -> sourceName.equals(source.path("source").asText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing settings source: " + sourceName))
            .path("settings");
    }
}
