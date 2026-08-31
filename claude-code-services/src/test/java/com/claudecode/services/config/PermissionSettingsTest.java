package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the standalone permission-settings facade's source ordering, trusted acknowledgement
 * boundaries, strict execution reads, and raw-preserving editable writes.
 */
class PermissionSettingsTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private String originalDirectory;
    private Path home;
    private Path project;

    @BeforeEach
    void redirectSettingsSources() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDirectory = System.getProperty("user.dir");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project.resolve(".claude"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true, project.toString());
        SettingsSnapshots.invalidateForReload();
    }

    @AfterEach
    void restoreSettingsSources() {
        SettingsSources.clearFlagSettings();
        restoreProperty("user.home", originalHome);
        restoreProperty("user.dir", originalDirectory);
        String cwd = originalDirectory == null ? project.toString() : originalDirectory;
        SettingsSources.configureAllowedSettingSources(true, true, true, cwd);
        SettingsSnapshots.invalidateForReload();
    }

    @Test
    void loadPermissionRules_preservesSourceIdentityWhenEditablePathsAlias() throws Exception {
        Path sharedSettings = home.resolve(".claude/settings.json");
        Files.writeString(sharedSettings, "{\"permissions\":{\"allow\":[\"Read\"]}}");
        System.setProperty("user.dir", home.toString());
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS), home.toString());

        List<PermissionRule> rules = PermissionSettings.loadPermissionRules(home.toString());

        assertEquals(List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS),
            rules.stream().map(PermissionRule::source).toList());
    }

    @Test
    void loadPermissionRules_usesConfiguredSourceOrderAndLabelsEachRule() throws Exception {
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"permissions\":{\"allow\":[\"Read\"]}}");
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"permissions\":{\"deny\":[\"Bash(rm *)\"]}}");
        Files.writeString(project.resolve(".claude/settings.local.json"),
            "{\"permissions\":{\"ask\":[\"WebFetch\"]}}");
        SettingsSources.applyFlagSettings(json("{\"permissions\":{\"allow\":[\"Glob\"]}}"));

        List<PermissionRule> rules = PermissionSettings.loadPermissionRules(project.toString());

        assertEquals(List.of(
            RuleSource.USER_SETTINGS,
            RuleSource.PROJECT_SETTINGS,
            RuleSource.LOCAL_SETTINGS,
            RuleSource.FLAG_SETTINGS), rules.stream().map(PermissionRule::source).toList());
        assertEquals(List.of(
            PermissionBehavior.ALLOW,
            PermissionBehavior.DENY,
            PermissionBehavior.ASK,
            PermissionBehavior.ALLOW), rules.stream().map(PermissionRule::behavior).toList());
    }

    @Test
    void managedOnlyPolicy_usesOnlyPolicyRules() throws Exception {
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"permissions\":{\"allow\":[\"Read\"]}}");
        JsonNode policy = json("""
            {
              "allowManagedPermissionRulesOnly": true,
              "permissions": {"deny": ["Bash(rm *)"]}
            }
            """);

        List<PermissionRule> rules = PermissionSettings.loadPermissionRules(project.toString(), policy);

        assertTrue(PermissionSettings.allowsManagedPermissionRulesOnly(policy));
        assertEquals(List.of(RuleSource.POLICY_SETTINGS),
            rules.stream().map(PermissionRule::source).toList());
        assertEquals(List.of(PermissionBehavior.DENY),
            rules.stream().map(PermissionRule::behavior).toList());
    }

    @Test
    void loadPermissionRulesFromFile_isStrictWhileEditableWritesPreserveInvalidSiblings()
            throws Exception {
        Path malformed = tempDir.resolve("malformed-settings.json");
        Files.writeString(malformed, "{ malformed JSON");

        assertThrows(SettingsParseException.class, () ->
            PermissionSettings.loadPermissionRulesFromFile(malformed, RuleSource.USER_SETTINGS));

        Path projectSettings = project.resolve(".claude/settings.json");
        Files.writeString(projectSettings, "{\"model\":7,\"permissions\":{\"allow\":[]}}");

        assertTrue(PermissionSettings.loadPermissionRulesFromFile(
            projectSettings, RuleSource.PROJECT_SETTINGS).isEmpty(),
            "an invalid execution source must not contribute partially validated rules");

        PermissionSettings.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Read", RuleSource.PROJECT_SETTINGS);

        JsonNode saved = JsonUtils.parseTree(Files.readString(projectSettings));
        assertEquals(7, saved.path("model").asInt(),
            "editing must retain unrelated schema-invalid fields");
        assertEquals("Read", saved.path("permissions").path("allow").get(0).asText());
    }

    @Test
    void permissionModeAndAcknowledgementReadersFollowTheirTrustBoundaries() throws Exception {
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"permissions\":{\"defaultMode\":\"plan\"}}");
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"permissions\":{\"defaultMode\":\"acceptEdits\"},"
                + "\"skipDangerousModePermissionPrompt\":true,"
                + "\"skipAutoPermissionPrompt\":true}");
        Files.writeString(project.resolve(".claude/settings.local.json"),
            "{\"permissions\":{\"defaultMode\":\"bypassPermissions\","
                + "\"disableBypassPermissionsMode\":\"disable\"},"
                + "\"skipDangerousModePermissionPrompt\":true}");

        assertEquals("bypassPermissions", PermissionSettings.loadDefaultPermissionMode());
        assertTrue(PermissionSettings.isBypassPermissionsModeDisabled());
        assertTrue(PermissionSettings.hasSkipDangerousModePermissionPrompt(),
            "local acknowledgement is trusted for bypass mode");
        assertFalse(PermissionSettings.hasSkipAutoPermissionPrompt(),
            "project and local settings must not suppress the auto-mode prompt");

        SettingsSources.applyFlagSettings(json("{\"skipAutoPermissionPrompt\":true}"));
        assertTrue(PermissionSettings.hasSkipAutoPermissionPrompt());
    }

    @Test
    void useAutoModeDuringPlanDefaultsTrueAndAnyTrustedFalseWins() throws Exception {
        assertTrue(PermissionSettings.useAutoModeDuringPlan());

        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"useAutoModeDuringPlan\":false}");
        assertTrue(PermissionSettings.useAutoModeDuringPlan(),
            "repository-controlled project settings are excluded");

        Files.writeString(project.resolve(".claude/settings.local.json"),
            "{\"useAutoModeDuringPlan\":false}");
        assertFalse(PermissionSettings.useAutoModeDuringPlan());
    }

    @Test
    void disableAutoModeKillsTheSynchronousPlanAutoGateFromEitherReleasedLocation()
            throws Exception {
        assertTrue(PermissionSettings.isAutoModeGateEnabledBySettings());

        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"disableAutoMode\":\"disable\"}");
        assertFalse(PermissionSettings.isAutoModeGateEnabledBySettings());

        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"permissions\":{\"disableAutoMode\":\"disable\"}}");
        assertFalse(PermissionSettings.isAutoModeGateEnabledBySettings());
    }

    @Test
    void permissionWritersDelegateToEditableDestinationsAndPreserveSiblings() throws Exception {
        Path localSettings = project.resolve(".claude/settings.local.json");
        Files.writeString(localSettings,
            "{\"permissions\":{\"deny\":[\"Bash(rm *)\"]},\"language\":\"English\"}");

        PermissionSettings.saveAdditionalDirectoryToLocalSettings(project.toString(), "/tmp/one");
        PermissionSettings.addAdditionalDirectories(project.toString(), List.of("/tmp/one", "/tmp/two"),
            RuleSource.LOCAL_SETTINGS);
        PermissionSettings.removeAdditionalDirectories(project.toString(), List.of("/tmp/one"),
            RuleSource.LOCAL_SETTINGS);
        PermissionSettings.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Task", RuleSource.LOCAL_SETTINGS);
        PermissionSettings.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Agent", RuleSource.LOCAL_SETTINGS);
        PermissionSettings.removePermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Task", RuleSource.LOCAL_SETTINGS);
        PermissionSettings.replacePermissionRules(project.toString(), PermissionBehavior.ASK,
            List.of("WebFetch", "Read"), RuleSource.LOCAL_SETTINGS);
        PermissionSettings.saveDefaultPermissionMode(project.toString(), "plan", RuleSource.LOCAL_SETTINGS);

        JsonNode saved = JsonUtils.parseTree(Files.readString(localSettings));
        assertEquals("English", saved.path("language").asText());
        assertEquals("Bash(rm *)", saved.path("permissions").path("deny").get(0).asText());
        assertEquals(List.of("/tmp/two"), strings(saved.path("permissions").path("additionalDirectories")));
        assertTrue(saved.path("permissions").path("allow").isEmpty(),
            "normalised aliases must be removed together");
        assertEquals(List.of("WebFetch", "Read"), strings(saved.path("permissions").path("ask")));
        assertEquals("plan", saved.path("permissions").path("defaultMode").asText());
    }

    @Test
    void defaultUserWritesAndDangerousPromptAcknowledgementRoundTrip() throws Exception {
        PermissionSettings.saveDefaultPermissionMode("acceptEdits");
        PermissionSettings.saveSkipDangerousModePermissionPrompt();

        assertEquals("acceptEdits", PermissionSettings.loadDefaultPermissionMode());
        assertTrue(PermissionSettings.hasSkipDangerousModePermissionPrompt());
        JsonNode saved = JsonUtils.parseTree(Files.readString(home.resolve(".claude/settings.json")));
        assertTrue(saved.path("skipDangerousModePermissionPrompt").asBoolean());
    }

    @Test
    void loadAdditionalDirectories_unionsEnabledSourcesInOrderAndDeduplicates() throws Exception {
        Files.writeString(home.resolve(".claude/settings.json"),
            "{\"permissions\":{\"additionalDirectories\":[\"/tmp/user\",\"/tmp/shared\"]}}");
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"permissions\":{\"additionalDirectories\":[\"/tmp/project\",\"/tmp/shared\"]}}");
        Files.writeString(project.resolve(".claude/settings.local.json"),
            "{\"permissions\":{\"additionalDirectories\":[\"/tmp/local\"]}}");
        SettingsSources.applyFlagSettings(json(
            "{\"permissions\":{\"additionalDirectories\":[\"/tmp/flag\",\"/tmp/shared\"]}}"));

        assertEquals(List.of("/tmp/user", "/tmp/shared", "/tmp/project", "/tmp/local", "/tmp/flag"),
            PermissionSettings.loadAdditionalDirectories(project.toString()));
    }

    private static JsonNode json(String value) {
        return JsonUtils.parseTree(value);
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
            .map(JsonNode::asText)
            .toList();
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
