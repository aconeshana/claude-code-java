package com.claudecode.services.config;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

class SettingsSourcesContractTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsOnlyNonEmptySourcesInLowToHighOrderAndMergesLaterValues() throws Exception {
        Path user = tempDir.resolve("user.json");
        Path project = tempDir.resolve("project.json");
        Path local = tempDir.resolve("local.json");
        Files.writeString(user, "{\"model\":\"user\",\"nested\":{\"a\":1}}");
        Files.writeString(project, "{}");
        Files.writeString(local, "{\"model\":\"local\",\"nested\":{\"b\":2}}");

        ObjectNode result = SettingsSnapshots.withSources(List.of(
            Map.entry("userSettings", user),
            Map.entry("projectSettings", project),
            Map.entry("localSettings", local),
            Map.entry("policySettings", tempDir.resolve("missing.json"))));

        assertEquals("local", result.path("effective").path("model").asText());
        assertEquals(1, result.path("effective").path("nested").path("a").asInt());
        assertEquals(2, result.path("effective").path("nested").path("b").asInt());
        assertEquals(List.of("userSettings", "localSettings"),
            StreamSupport.stream(
                    result.path("sources").spliterator(), false)
                .map(source -> source.path("source").asText())
                .toList());
    }

    @Test
    void settingSourceSelectionSuppressesDisabledPathReads() throws Exception {
        Path project = tempDir.resolve("project/.claude/settings.json");
        Path local = tempDir.resolve("project/.claude/settings.local.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, "{\"language\":\"Project\"}");
        Files.writeString(local, "{\"language\":\"Local\"}");

        SettingsSources.configureAllowedSettingSources(true, false, false,
            tempDir.resolve("project").toString());
        try {
            assertTrue(SettingsTreeReader.readAccepted(project, true).isEmpty());
            assertTrue(SettingsTreeReader.readAccepted(local, true).isEmpty());
        } finally {
            SettingsSources.configureAllowedSettingSources(true, true, true,
                tempDir.resolve("project").toString());
        }
        assertEquals("Local", RuntimeSettings.loadLayeredString("language", List.of(project, local)));
    }

    @Test
    void layeredStringPreservesSurroundingWhitespaceLikeTsStringSchema() throws Exception {
        Path settings = tempDir.resolve("settings.json");
        Files.writeString(settings, "{\"language\":\"  Japanese  \"}");

        assertEquals("  Japanese  ",
            RuntimeSettings.loadLayeredString("language", List.of(settings)));
    }

    @Test
    void environmentValuesUseTsCoerceStringSemantics() throws Exception {
        Path settings = tempDir.resolve("env-settings.json");
        Files.writeString(settings,
            "{\"env\":{"
                + "\"BOOL\":true,\"NUMBER\":1.0,\"DECIMAL\":1.50,"
                + "\"LARGE\":1e21,\"SMALL\":1e-7,\"UNSAFE\":9007199254740993,"
                + "\"ARRAY\":[1,\"x\",null,{\"nested\":true}],"
                + "\"OBJECT\":{\"nested\":true},\"NULL\":null}}" );

        ObjectNode result = SettingsSnapshots.withSources(List.of(
            Map.entry("userSettings", settings)));
        ObjectNode env = (ObjectNode) result.path("effective").path("env");

        assertEquals("true", env.path("BOOL").asText());
        assertEquals("1", env.path("NUMBER").asText());
        assertEquals("1.5", env.path("DECIMAL").asText());
        assertEquals("1e+21", env.path("LARGE").asText());
        assertEquals("1e-7", env.path("SMALL").asText());
        assertEquals("9007199254740992", env.path("UNSAFE").asText());
        assertEquals("1,x,,[object Object]", env.path("ARRAY").asText());
        assertEquals("[object Object]", env.path("OBJECT").asText());
        assertEquals("null", env.path("NULL").asText());
    }

    @Test
    void settingsWithErrorsKeepsValidSourcesAndDeduplicatesDiagnostics() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("errors-home");
        Path cwd = tempDir.resolve("errors-cwd");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(cwd.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"model\":\"valid\",\"cleanupPeriodDays\":\"bad\"}");
        Files.writeString(cwd.resolve(".claude/settings.json"),
            "{\"model\":\"valid\",\"cleanupPeriodDays\":\"bad\"}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", cwd.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS), cwd.toString());
            SettingsWithErrors result = SettingsDiagnostics.loadSettingsWithErrors(cwd.toString());
            assertEquals(2, result.errors().stream()
                .filter(error ->Strings.CS.equals( "cleanupPeriodDays", error.path())).count());
            assertTrue(result.settings().isEmpty(), "invalid files are not merged into effective settings");
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? cwd.toString() : originalDir);
            SettingsSources.clearFlagSettings();
        }
    }

    @Test
    void managedEnvironmentAppliesTrustedAndSafeValuesWithoutMutatingProcessEnv() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("env-home");
        Path cwd = tempDir.resolve("env-cwd");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(cwd.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"env\":{\"ANTHROPIC_API_KEY\":\"trusted\"}}");
        Files.writeString(cwd.resolve(".claude/settings.json"),
            "{\"env\":{\"CLAUDE_CODE_TEST_UNSAFE_SETTINGS_ENV\":\"bad\",\"BASH_MAX_OUTPUT_LENGTH\":\"42\"}}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", cwd.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS), cwd.toString());
            ManagedEnvironmentApplier.applySafeConfigEnvironmentVariables(cwd.toString());
            assertEquals("trusted", SubprocessEnvironment.get("ANTHROPIC_API_KEY"));
            assertEquals("42", SubprocessEnvironment.get("BASH_MAX_OUTPUT_LENGTH"));
            assertNull(SubprocessEnvironment.get("CLAUDE_CODE_TEST_UNSAFE_SETTINGS_ENV"));
        } finally {
            SubprocessEnvironment.clearSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? cwd.toString() : originalDir);
        }
    }

    @Test
    void safeEnvironmentAllowlistUsesLocaleIndependentKeyMatching() throws Exception {
        Locale originalLocale = Locale.getDefault();
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("locale-env-home");
        Path cwd = tempDir.resolve("locale-env-cwd");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(cwd.resolve(".claude"));
        Files.writeString(cwd.resolve(".claude/settings.json"),
            "{\"env\":{\"anthropic_model\":\"settings-model\"}}");
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", cwd.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS), cwd.toString());

            ManagedEnvironmentApplier.applySafeConfigEnvironmentVariables(cwd.toString());

            assertEquals("settings-model", SubprocessEnvironment.get("anthropic_model"));
        } finally {
            SubprocessEnvironment.clearSettings();
            Locale.setDefault(originalLocale);
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? cwd.toString() : originalDir);
        }
    }

    @Test
    void catchGuardedSettingsAreNormalizedLikeTsSchema() throws Exception {
        Path settings = tempDir.resolve("catch-settings.json");
        Files.writeString(settings,
            "{\"effortLevel\":\"turbo\","
                + "\"strictPluginOnlyCustomization\":[\"skills\",\"futureSurface\",7]}");

        JsonNode effective = SettingsSnapshots.withSources(List.of(
            Map.entry("userSettings", settings))).path("effective");

        assertTrue(effective.isObject());
        assertFalse(effective.has("effortLevel"), "TS .catch(undefined) drops invalid effort values from parsed settings");
        assertEquals(List.of("skills"),
            StreamSupport.stream(
                    effective.path("strictPluginOnlyCustomization").spliterator(), false)
                .map(JsonNode::asText)
                .toList());
    }

    @Test
    void acceptedSettingsStripStrictNestedUnknownKeysButKeepPassthroughObjects() throws Exception {
        Path settings = tempDir.resolve("strict-nested.json");
        Files.writeString(settings, """
            {
              "statusLine": {"type":"command", "command":"echo", "extra":true},
              "hooks": {"SessionStart": [{"matcher":"x", "matcherExtra":true,
                "hooks":[{"type":"command", "command":"echo", "hookExtra":true}]}]},
              "sandbox": {"enabled":true, "sandboxExtra":true,
                "network":{"allowedDomains":["example.com"], "networkExtra":true}},
              "permissions": {"defaultMode":"default", "permissionsExtra":true}
            }
            """);

        JsonNode effective = SettingsSnapshots.withSources(List.of(
            Map.entry("userSettings", settings))).path("effective");

        assertFalse(effective.path("statusLine").has("extra"));
        assertFalse(effective.path("hooks").path("SessionStart").get(0).has("matcherExtra"));
        assertFalse(effective.path("hooks").path("SessionStart").get(0)
            .path("hooks").get(0).has("hookExtra"));
        assertFalse(effective.path("sandbox").path("network").has("networkExtra"));
        assertTrue(effective.path("sandbox").has("sandboxExtra"));
        assertTrue(effective.path("permissions").has("permissionsExtra"));
    }

    @Test
    void sharedUserAndProjectPathStillReadsTheEnabledSource() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"language\":\"project-source\"}");
        try {
            // With cwd == $HOME, userSettings and projectSettings intentionally
            // resolve to the same physical file. Disabling user must not disable
            // the project source that remains selected.
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS),
                home.toString());

            ObjectNode result = SettingsSnapshots.withSources(home.toString());
            assertEquals("project-source", result.path("effective").path("language").asText());
            assertEquals("projectSettings",
                result.path("sources").get(0).path("source").asText());
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }

    @Test
    void effectiveMergeDeduplicatesSharedUserAndProjectPath() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home-for-effective-dedup");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"customObjects\":[{\"name\":\"one\"}]}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS),
                home.toString());

            ObjectNode result = SettingsSnapshots.withSources(home.toString());
            assertEquals(1, result.path("effective").path("customObjects").size(),
                "the same physical settings file must merge only once");
            assertEquals(2, result.path("sources").size(),
                "source-labelled entries remain distinct even when paths coincide");
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }

    @Test
    void effectiveMergeDeduplicatesFlagFileButKeepsInlineOverlay() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home-for-flag-effective-dedup");
        Path project = tempDir.resolve("project-for-flag-effective-dedup");
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.createDirectories(project);
        Files.writeString(settings, "{\"customArray\":[\"file\"]}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), project.toString());
            SettingsSources.setFlagSettingsPath(settings);
            SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
                "{\"customArray\":[\"inline\"]}"));

            ObjectNode result = SettingsSnapshots.withSources(project.toString());
            assertEquals(List.of("file", "inline"),
                StreamSupport.stream(
                        result.path("effective").path("customArray").spliterator(), false)
                    .map(JsonNode::asText).toList());
            assertEquals(2, result.path("sources").size(),
                "the aliased flag source remains visible alongside userSettings");
            assertEquals(List.of("file", "inline"),
                StreamSupport.stream(
                        result.path("sources").get(1).path("settings").path("customArray")
                            .spliterator(), false)
                    .map(JsonNode::asText).toList());
        } finally {
            SettingsSources.clearFlagSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void effectiveMergeKeepsInlineOverlayWhenAliasedFlagFileIsEmpty() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home-for-empty-flag-effective-dedup");
        Path project = tempDir.resolve("project-for-empty-flag-effective-dedup");
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.createDirectories(project);
        Files.writeString(settings, "{}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), project.toString());
            SettingsSources.setFlagSettingsPath(settings);
            SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
                "{\"model\":\"inline-model\"}"));

            ObjectNode result = SettingsSnapshots.withSources(project.toString());
            assertEquals("inline-model", result.path("effective").path("model").asText());
            assertEquals(1, result.path("sources").size(),
                "TS getSettingsWithSources omits the empty aliased editable source");
            assertEquals("flagSettings", result.path("sources").get(0).path("source").asText());
            assertEquals("inline-model", result.path("sources").get(0).path("settings")
                .path("model").asText());
        } finally {
            SettingsSources.clearFlagSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void claudeMdExcludesDeduplicatesRepeatedPatternsAcrossAliasedSources() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home-for-excludes-dedup");
        Path project = tempDir.resolve("project-for-excludes-dedup");
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.createDirectories(project);
        Files.writeString(settings, "{\"claudeMdExcludes\":[\"**/CLAUDE.md\"]}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), project.toString());
            SettingsSources.setFlagSettingsPath(settings);

            assertEquals(List.of("**/CLAUDE.md"),
                WorkspaceSettings.loadClaudeMdExcludes(project.toString()));
        } finally {
            SettingsSources.clearFlagSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void arrayMergeUsesJavascriptNumberEquality() throws Exception {
        Path user = tempDir.resolve("numeric-user.json");
        Path project = tempDir.resolve("numeric-project.json");
        Files.writeString(user, "{\"customArray\":[1]}");
        Files.writeString(project, "{\"customArray\":[1.0,2]}");

        ObjectNode result = SettingsSnapshots.withSources(List.of(
            Map.entry("userSettings", user), Map.entry("projectSettings", project)));
        assertEquals(2, result.path("effective").path("customArray").size());
        assertEquals(1, result.path("effective").path("customArray").get(0).asInt());
        assertEquals(2, result.path("effective").path("customArray").get(1).asInt());
    }

    @Test
    void flagSourceRemainsEnabledWhenItsPathMatchesExcludedUserFile() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("home-for-flag");
        Path project = tempDir.resolve("flag-project");
        Path userSettings = home.resolve(".claude/settings.json");
        Files.createDirectories(userSettings.getParent());
        Files.createDirectories(project);
        Files.writeString(userSettings, "{\"language\":\"flag-file\"}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS),
                project.toString());
            SettingsSources.setFlagSettingsPath(userSettings);

            ObjectNode result = SettingsSnapshots.withSources(project.toString());
            assertEquals("flag-file", result.path("effective").path("language").asText());
            assertEquals("flagSettings",
                result.path("sources").get(0).path("source").asText());

            SettingsWithErrors startup =
                SettingsDiagnostics.loadSettingsWithErrors(project.toString());
            assertEquals("flag-file", startup.settings().path("language").asText(),
                "startup settings loading must keep the always-enabled flag tier");
        } finally {
            SettingsSources.clearFlagSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void permissionRulesKeepSourceEntriesWhenUserAndProjectShareAFile() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("permission-home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"permissions\":{\"allow\":[\"Read(*)\"]}}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS),
                home.toString());

            List<PermissionRule> rules = PermissionSettings.loadPermissionRules(home.toString());
            assertEquals(2, rules.size(), "TS loads the shared file once per enabled source");
            assertEquals(PermissionBehavior.ALLOW, rules.getFirst().behavior());
            assertEquals(RuleSource.USER_SETTINGS, rules.getFirst().source());
            assertEquals(RuleSource.PROJECT_SETTINGS, rules.get(1).source());
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }

    @Test
    void cleanupGuardDoesNotFallBackWhenExplicitRetentionHasOtherSettingsErrors() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("cleanup-home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"cleanupPeriodDays\":7,\"permissions\":{\"allow\":[123]}}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), home.toString());

            assertTrue(SettingsDiagnostics.rawSettingsContainsKey("cleanupPeriodDays"));
            assertTrue(SettingsDiagnostics.shouldSkipFileHistoryCleanup());
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }

    @Test
    void cleanupGuardIncludesMcpValidationErrors() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tempDir.resolve("cleanup-mcp-home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"cleanupPeriodDays\":7}");
        Files.writeString(home.resolve(".mcp.json"), "{not valid json");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS),
                home.toString());

            assertTrue(SettingsDiagnostics.rawSettingsContainsKey("cleanupPeriodDays"));
            assertTrue(SettingsDiagnostics.shouldSkipFileHistoryCleanup(),
                "MCP validation errors are part of TS getSettingsWithAllErrors()");
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }
}
