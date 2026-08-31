package com.claudecode.services.config;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the editable settings persistence boundary.
 */
class SettingsEditorTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private Path home;
    private Path project;

    @BeforeEach
    void redirectUserSettingsHome() throws IOException {
        originalHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        Files.createDirectories(home);
        Files.createDirectories(project);
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreUserSettingsHome() {
        if (originalHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void writeUserBoolean_preservesUnrelatedSettings() throws IOException {
        Path userSettings = SettingsPaths.userSettingsPath();
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, "{\"model\":\"opus\"}");

        SettingsEditor.writeUserBoolean("alwaysThinkingEnabled", true);

        JsonNode root = JsonUtils.readJson(userSettings);
        assertEquals("opus", root.path("model").asText());
        assertTrue(root.path("alwaysThinkingEnabled").asBoolean());
    }

    @Test
    void writeUserStringPersistsModelInSettingsJson() throws IOException {
        Path userSettings = SettingsPaths.userSettingsPath();
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, "{\"effortLevel\":\"high\"}");

        SettingsEditor.writeUserString("model", "gpt-5.6-sol");

        JsonNode root = JsonUtils.readJson(userSettings);
        assertEquals("gpt-5.6-sol", root.path("model").asText());
        assertEquals("high", root.path("effortLevel").asText());
    }

    @Test
    void writeLocalBoolean_usesSuppliedProjectDirectory() throws IOException {
        Path otherProject = tempDir.resolve("other-project");
        Files.createDirectories(otherProject);

        SettingsEditor.writeLocalBoolean(otherProject.toString(), "prefersReducedMotion", true);

        Path localSettings = SettingsPaths.localSettingsPath(otherProject.toString());
        assertTrue(Files.exists(localSettings));
        assertTrue(JsonUtils.readJson(localSettings).path("prefersReducedMotion").asBoolean());
        assertFalse(Files.exists(SettingsPaths.localSettingsPath(project.toString())));
    }

    @Test
    void addPermissionRule_preservesOrderAndDedupesEquivalentLegacyAliases() throws IOException {
        Path userSettings = SettingsPaths.userSettingsPath();
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings,
            "{\"permissions\":{\"deny\":[\"Bash(rm *)\"]},\"model\":\"opus\"}");

        SettingsEditor.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Task", RuleSource.USER_SETTINGS);
        SettingsEditor.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Agent", RuleSource.USER_SETTINGS);
        SettingsEditor.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Read", RuleSource.USER_SETTINGS);

        JsonNode root = JsonUtils.readJson(userSettings);
        assertEquals(List.of("Task", "Read"), strings(root.path("permissions").path("allow")));
        assertEquals(List.of("Bash(rm *)"), strings(root.path("permissions").path("deny")));
        assertEquals("opus", root.path("model").asText());
    }

    @Test
    void removePermissionRule_normalizesWildcardWideSyntaxAndKeepsSiblings() throws IOException {
        Path localSettings = SettingsPaths.localSettingsPath(project.toString());
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings,
            "{\"permissions\":{\"allow\":[\"Bash(*)\",\"Read\"],\"deny\":[\"Bash(rm *)\"]}}");

        SettingsEditor.removePermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Bash", RuleSource.LOCAL_SETTINGS);

        JsonNode permissions = JsonUtils.readJson(localSettings).path("permissions");
        assertEquals(List.of("Read"), strings(permissions.path("allow")));
        assertEquals(List.of("Bash(rm *)"), strings(permissions.path("deny")));
    }

    @Test
    void removePermissionRule_doesNotRewriteWhenNoEquivalentRuleExists() throws IOException {
        Path localSettings = SettingsPaths.localSettingsPath(project.toString());
        Files.createDirectories(localSettings.getParent());
        String original = "{\"permissions\":{\"allow\":[\"Read\"]}}";
        Files.writeString(localSettings, original);

        SettingsEditor.removePermissionRule(project.toString(), PermissionBehavior.ALLOW,
            "Write", RuleSource.LOCAL_SETTINGS);

        assertEquals(original, Files.readString(localSettings));
    }

    @Test
    void replacePermissionRules_replacesOnlyTheRequestedBehaviorArray() throws IOException {
        Path projectSettings = SettingsPaths.projectSettingsPath(project.toString());
        Files.createDirectories(projectSettings.getParent());
        Files.writeString(projectSettings,
            "{\"permissions\":{\"allow\":[\"Read\"],\"deny\":[\"Bash(rm *)\"]},\"model\":\"opus\"}");

        SettingsEditor.replacePermissionRules(project.toString(), PermissionBehavior.ALLOW,
            Arrays.asList("Write", "", null, "Read"), RuleSource.PROJECT_SETTINGS);

        JsonNode root = JsonUtils.readJson(projectSettings);
        assertEquals(List.of("Write", "Read"), strings(root.path("permissions").path("allow")));
        assertEquals(List.of("Bash(rm *)"), strings(root.path("permissions").path("deny")));
        assertEquals("opus", root.path("model").asText());
    }

    @Test
    void writeDefaultPermissionMode_writesThenRemovesOnlyThatKey() throws IOException {
        Path projectSettings = SettingsPaths.projectSettingsPath(project.toString());
        Files.createDirectories(projectSettings.getParent());
        Files.writeString(projectSettings, "{\"permissions\":{\"allow\":[\"Read\"]}}");

        SettingsEditor.writeDefaultPermissionMode(project.toString(), "plan", RuleSource.PROJECT_SETTINGS);
        assertEquals("plan", JsonUtils.readJson(projectSettings)
            .path("permissions").path("defaultMode").asText());

        SettingsEditor.writeDefaultPermissionMode(project.toString(), null, RuleSource.PROJECT_SETTINGS);
        JsonNode permissions = JsonUtils.readJson(projectSettings).path("permissions");
        assertFalse(permissions.has("defaultMode"));
        assertEquals(List.of("Read"), strings(permissions.path("allow")));
    }

    @Test
    void addAdditionalDirectories_appendsUnseenDirectoriesInInputOrder() throws IOException {
        Path projectSettings = SettingsPaths.projectSettingsPath(project.toString());
        Files.createDirectories(projectSettings.getParent());
        Files.writeString(projectSettings,
            "{\"permissions\":{\"additionalDirectories\":[\"/one\"]}}");

        SettingsEditor.addAdditionalDirectories(project.toString(),
            Arrays.asList("/one", "/two", "/two", "", null, "/three"),
            RuleSource.PROJECT_SETTINGS);

        assertEquals(List.of("/one", "/two", "/three"), strings(JsonUtils.readJson(projectSettings)
            .path("permissions").path("additionalDirectories")));
    }

    @Test
    void removeAdditionalDirectories_removesRequestedEntriesAndPreservesOthers() throws IOException {
        Path projectSettings = SettingsPaths.projectSettingsPath(project.toString());
        Files.createDirectories(projectSettings.getParent());
        Files.writeString(projectSettings,
            "{\"permissions\":{\"additionalDirectories\":[\"/one\",\"/two\",\"/three\"]}}");

        SettingsEditor.removeAdditionalDirectories(project.toString(), List.of("/one", "/three"),
            RuleSource.PROJECT_SETTINGS);

        assertEquals(List.of("/two"), strings(JsonUtils.readJson(projectSettings)
            .path("permissions").path("additionalDirectories")));
    }

    @Test
    void removeAdditionalDirectories_createsAnEmptyArrayForAMissingSourceLikeTs() throws IOException {
        Path projectSettings = SettingsPaths.projectSettingsPath(project.toString());

        SettingsEditor.removeAdditionalDirectories(project.toString(), List.of("/one"),
            RuleSource.PROJECT_SETTINGS);

        assertEquals(List.of(), strings(JsonUtils.readJson(projectSettings)
            .path("permissions").path("additionalDirectories")));
    }

    @Test
    void addAdditionalDirectoryToLocalSettings_dedupesAndKeepsUnrelatedFields() throws IOException {
        Path localSettings = SettingsPaths.localSettingsPath(project.toString());
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"prefersReducedMotion\":true}");

        SettingsEditor.addAdditionalDirectoryToLocalSettings(project.toString(), "/one");
        SettingsEditor.addAdditionalDirectoryToLocalSettings(project.toString(), "/one");

        JsonNode root = JsonUtils.readJson(localSettings);
        assertTrue(root.path("prefersReducedMotion").asBoolean());
        assertEquals(List.of("/one"), strings(root.path("permissions").path("additionalDirectories")));
    }

    @Test
    void tierAwareWrites_rejectReadOnlyAndNonSettingsTiers() {
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.addPermissionRule(project.toString(), PermissionBehavior.ALLOW,
                "Read", RuleSource.POLICY_SETTINGS));
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.replacePermissionRules(project.toString(), PermissionBehavior.ALLOW,
                List.of("Read"), RuleSource.FLAG_SETTINGS));
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.writeDefaultPermissionMode(project.toString(), "plan", RuleSource.SESSION));
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.addAdditionalDirectories(project.toString(), List.of("/one"), RuleSource.COMMAND));
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.removeAdditionalDirectories(project.toString(), List.of("/one"), RuleSource.SKILL));
    }

    @Test
    void permissionRuleWriters_rejectPassthroughBehavior() {
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.addPermissionRule(project.toString(), PermissionBehavior.PASSTHROUGH,
                "Read", RuleSource.USER_SETTINGS));
        assertThrows(IllegalArgumentException.class, () ->
            SettingsEditor.replacePermissionRules(project.toString(), PermissionBehavior.PASSTHROUGH,
                List.of("Read"), RuleSource.USER_SETTINGS));
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .toList();
    }
}
