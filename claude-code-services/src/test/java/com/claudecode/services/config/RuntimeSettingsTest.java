package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral coverage for the runtime-facing scalar settings facade.
 */
class RuntimeSettingsTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private String originalDir;
    private Path home;
    private Path cwd;
    private Path userSettings;
    private Path projectSettings;
    private Path localSettings;

    @BeforeEach
    void isolateSettingsSources() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        home = tempDir.resolve("home");
        cwd = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(cwd.resolve(".claude"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", cwd.toString());
        userSettings = SettingsPaths.userSettingsPath();
        projectSettings = SettingsPaths.projectSettingsPath(cwd.toString());
        localSettings = SettingsPaths.localSettingsPath(cwd.toString());
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS,
                RuleSource.LOCAL_SETTINGS), cwd.toString(), true);
    }

    @AfterEach
    void restoreSettingsSources() {
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        restoreProperty("user.home", originalHome);
        restoreProperty("user.dir", originalDir);
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalDir == null ? cwd.toString() : originalDir);
    }

    @Test
    void readsValidatedEffectiveValuesWithLaterSourcePrecedence() throws Exception {
        Files.writeString(userSettings, """
            {
              "alwaysThinkingEnabled": true,
              "spinnerTipsEnabled": false,
              "syntaxHighlightingDisabled": true,
              "skipWebFetchPreflight": false,
              "autoMemoryEnabled": false,
              "autoDreamEnabled": false,
              "cleanupPeriodDays": 14,
              "language": " user language ",
              "outputStyle": "user-style",
              "effortLevel": "low",
              "askUserQuestion.previewFormat": "html",
              "customRuntimeValue": {"fromUser": true}
            }
            """);
        Files.writeString(projectSettings, """
            {"alwaysThinkingEnabled": true, "language": "project language"}
            """);
        Files.writeString(localSettings, """
            {
              "alwaysThinkingEnabled": false,
              "prefersReducedMotion": true,
              "spinnerTipsEnabled": true,
              "syntaxHighlightingDisabled": false,
              "skipWebFetchPreflight": true,
              "autoMemoryEnabled": true,
              "includeGitInstructions": false,
              "awaySummaryEnabled": true,
              "agentProgressSummariesEnabled": true,
              "extractMemoriesEnabled": true,
              "teamMemoryEnabled": true,
              "cleanupPeriodDays": 0,
              "timeBasedMicrocompactEnabled": true,
              "timeBasedMicrocompactGapMinutes": 15,
              "timeBasedMicrocompactKeepRecent": 9,
              "language": " local language ",
              "outputStyle": "local-style",
              "effortLevel": "high",
              "askUserQuestion.previewFormat": "markdown",
              "customRuntimeValue": {"fromLocal": true}
            }
            """);

        assertFalse(RuntimeSettings.loadAlwaysThinkingEnabled());
        assertTrue(RuntimeSettings.loadPrefersReducedMotion());
        assertTrue(RuntimeSettings.loadSpinnerTipsEnabled());
        assertFalse(RuntimeSettings.loadSyntaxHighlightingDisabled());
        assertTrue(RuntimeSettings.loadSkipWebFetchPreflight());
        assertTrue(RuntimeSettings.loadAutoMemoryEnabled());
        assertFalse(RuntimeSettings.loadOptionalBoolean("includeGitInstructions"));
        assertFalse(RuntimeSettings.isAutoDreamEnabled(),
            "an explicit false must remain false regardless of the rollout cache");
        assertTrue(RuntimeSettings.loadAwaySummaryEnabled());
        assertTrue(RuntimeSettings.loadAgentProgressSummariesEnabled());
        assertTrue(RuntimeSettings.loadExtractMemoriesEnabled());
        assertTrue(RuntimeSettings.loadTeamMemoryEnabled());
        assertEquals(0, RuntimeSettings.loadCleanupPeriodDays());
        assertTrue(RuntimeSettings.loadTimeBasedMicrocompactEnabled());
        assertEquals(15, RuntimeSettings.loadTimeBasedMicrocompactGapMinutes());
        assertEquals(9, RuntimeSettings.loadTimeBasedMicrocompactKeepRecent());
        assertEquals(" local language ", RuntimeSettings.loadLanguage());
        assertEquals("local-style", RuntimeSettings.loadOutputStyleName());
        assertEquals("high", RuntimeSettings.loadEffortLevel());
        assertEquals("markdown", RuntimeSettings.loadAskUserQuestionPreviewFormat());
        assertEquals(" user language ", RuntimeSettings.loadUserSettingString("language"));
        assertNull(RuntimeSettings.loadUserSettingString("  "));

        ObjectNode effective = (ObjectNode) RuntimeSettings.loadEffectiveSetting("customRuntimeValue");
        assertTrue(effective.path("fromUser").asBoolean());
        assertTrue(effective.path("fromLocal").asBoolean());
        effective.put("callerMutation", true);
        assertFalse(RuntimeSettings.loadEffectiveSetting("customRuntimeValue")
            .has("callerMutation"), "callers must receive a detached effective node");
        assertNull(RuntimeSettings.loadEffectiveSetting(""));
    }

    @Test
    void usesReleasedDefaultsWhenNoAcceptedSettingSuppliesAValue() throws Exception {
        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled());
        assertFalse(RuntimeSettings.loadPrefersReducedMotion());
        assertTrue(RuntimeSettings.loadSpinnerTipsEnabled());
        assertFalse(RuntimeSettings.loadSyntaxHighlightingDisabled());
        assertFalse(RuntimeSettings.loadSkipWebFetchPreflight());
        assertTrue(RuntimeSettings.loadAutoMemoryEnabled());
        assertNull(RuntimeSettings.loadOptionalBoolean("includeGitInstructions"));
        assertTrue(RuntimeSettings.loadAwaySummaryEnabled(),
            "2.1.197 enables Session recap when the setting is absent");
        assertFalse(RuntimeSettings.loadAgentProgressSummariesEnabled());
        assertFalse(RuntimeSettings.loadExtractMemoriesEnabled());
        assertFalse(RuntimeSettings.loadTeamMemoryEnabled());
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays());
        assertEquals(2, RuntimeSettings.loadSubagentMaxDepth());
        assertFalse(RuntimeSettings.loadTimeBasedMicrocompactEnabled());
        assertEquals(60, RuntimeSettings.loadTimeBasedMicrocompactGapMinutes());
        assertEquals(5, RuntimeSettings.loadTimeBasedMicrocompactKeepRecent());
        assertNull(RuntimeSettings.loadLanguage());
        assertNull(RuntimeSettings.loadOutputStyleName());
        assertNull(RuntimeSettings.loadEffortLevel());
        assertNull(RuntimeSettings.loadAskUserQuestionPreviewFormat());

        Files.writeString(userSettings,
            "{\"alwaysThinkingEnabled\":\"not-a-boolean\",\"cleanupPeriodDays\":-1}");

        assertTrue(RuntimeSettings.loadAlwaysThinkingEnabled(),
            "an invalid source must not leak a malformed setting into the runtime view");
        assertEquals(30, RuntimeSettings.loadCleanupPeriodDays());
    }

    @Test
    void subagentMaxDepthIsUserGlobalAndRejectsInvalidValues() throws Exception {
        Files.writeString(userSettings, "{\"subagentMaxDepth\":5}");
        Files.writeString(projectSettings, "{\"subagentMaxDepth\":1}");
        Files.writeString(localSettings, "{\"subagentMaxDepth\":1}");
        assertEquals(5, RuntimeSettings.loadSubagentMaxDepth());

        Files.writeString(userSettings, "{\"subagentMaxDepth\":2.0}");
        assertEquals(2, RuntimeSettings.loadSubagentMaxDepth());

        RuntimeSettings.saveSubagentMaxDepth(4);
        assertEquals(4, JsonUtils.readJson(userSettings).path("subagentMaxDepth").asInt());
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeSettings.saveSubagentMaxDepth(0));
        assertThrows(IllegalArgumentException.class,
            () -> RuntimeSettings.saveSubagentMaxDepth(6));
    }

    @Test
    void savesBooleanPreferencesToTheirTsEditableTiers() throws Exception {
        Files.writeString(userSettings, "{\"language\":\"preserved\"}");
        Files.writeString(localSettings, "{\"outputStyle\":\"preserved\"}");

        RuntimeSettings.saveSyntaxHighlightingDisabled(true);
        RuntimeSettings.saveAutoMemoryEnabled(false);
        RuntimeSettings.saveAutoDreamEnabled(true);
        RuntimeSettings.saveAlwaysThinkingEnabled(false);
        RuntimeSettings.saveSpinnerTipsEnabled(false);
        RuntimeSettings.savePrefersReducedMotion(true);

        JsonNode savedUser = JsonUtils.readJson(userSettings);
        assertEquals("preserved", savedUser.path("language").asText());
        assertTrue(savedUser.path("syntaxHighlightingDisabled").asBoolean());
        assertFalse(savedUser.path("autoMemoryEnabled").asBoolean());
        assertTrue(savedUser.path("autoDreamEnabled").asBoolean());
        assertFalse(savedUser.path("alwaysThinkingEnabled").asBoolean());

        JsonNode savedLocal = JsonUtils.readJson(localSettings);
        assertEquals("preserved", savedLocal.path("outputStyle").asText());
        assertFalse(savedLocal.path("spinnerTipsEnabled").asBoolean());
        assertTrue(savedLocal.path("prefersReducedMotion").asBoolean());
        assertFalse(savedLocal.has("alwaysThinkingEnabled"),
            "always-thinking belongs to the user tier, not the local tier");

        assertFalse(RuntimeSettings.loadAlwaysThinkingEnabled());
        assertFalse(RuntimeSettings.loadSpinnerTipsEnabled());
        assertTrue(RuntimeSettings.loadPrefersReducedMotion());
        assertTrue(RuntimeSettings.loadSyntaxHighlightingDisabled());
        assertFalse(RuntimeSettings.loadAutoMemoryEnabled());
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
