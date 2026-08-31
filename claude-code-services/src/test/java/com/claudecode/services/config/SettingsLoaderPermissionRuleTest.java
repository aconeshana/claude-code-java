package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;


class SettingsPermissionRuleTest {

    @TempDir Path tempDir;

    private String originalHome;
    private Path homeDir;
    private Path projectDir;
    private Path userSettings;
    private Path projectSettings;
    private Path localSettings;

    @BeforeEach
    void redirect() throws IOException {
        originalHome = System.getProperty("user.home");
        homeDir = tempDir.resolve("home");
        projectDir = tempDir.resolve("project");
        Files.createDirectories(homeDir);
        Files.createDirectories(projectDir);
        System.setProperty("user.home", homeDir.toString());
        userSettings = homeDir.resolve(".claude").resolve("settings.json");
        projectSettings = projectDir.resolve(".claude").resolve("settings.json");
        localSettings = projectDir.resolve(".claude").resolve("settings.local.json");
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", originalHome);
    }

    @Test
    void addRule_writesToUserSettingsUnderPermissionsAllow() throws IOException {
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Bash(git *)", RuleSource.USER_SETTINGS);
        assertTrue(Files.isReadable(userSettings));
        String content = Files.readString(userSettings);
        assertTrue(Strings.CS.contains(content, "Bash(git *)"));
        assertTrue(Strings.CS.contains(content, "\"allow\""));
    }

    @Test
    void addRule_writesToProjectSettingsUnderPermissionsDeny() throws IOException {
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.DENY, "Bash(rm *)", RuleSource.PROJECT_SETTINGS);
        String content = Files.readString(projectSettings);
        assertTrue(Strings.CS.contains(content, "Bash(rm *)"));
        assertTrue(Strings.CS.contains(content, "\"deny\""));
    }

    @Test
    void addRule_writesToLocalSettingsUnderPermissionsAsk() throws IOException {
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ASK, "WebFetch", RuleSource.LOCAL_SETTINGS);
        String content = Files.readString(localSettings);
        assertTrue(Strings.CS.contains(content, "WebFetch"));
        assertTrue(Strings.CS.contains(content, "\"ask\""));
    }

    @Test
    void addRuleTwice_dedupes() throws IOException {
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.USER_SETTINGS);
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.USER_SETTINGS);
        long occurrences = Files.readString(userSettings).lines()
            .filter(line -> Strings.CS.contains(line, "\"Read\"")).count();
        assertEquals(1, occurrences);
    }

    @Test
    void addRule_dedupesLegacyAliasAfterRuleNormalization() throws IOException {
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW,
            "Task", RuleSource.USER_SETTINGS);
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW,
            "Agent", RuleSource.USER_SETTINGS);

        String content = Files.readString(userSettings);
        assertEquals(1, content.lines()
            .filter(line -> Strings.CS.contains(line, "\"Task\""))
            .count());
        assertFalse(Strings.CS.contains(content, "\"Agent\""));
    }

    @Test
    void addRulePreservesOtherKeysAndOtherArrays() throws IOException {
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, "{\"permissions\": {\"deny\": [\"Bash(rm *)\"]}, \"model\": \"opus\"}");
        PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.USER_SETTINGS);
        String content = Files.readString(userSettings);
        assertTrue(Strings.CS.contains(content, "Bash(rm *)"), "unrelated deny array must survive");
        assertTrue(Strings.CS.contains(content, "\"opus\""), "unrelated top-level key must survive");
        assertTrue(Strings.CS.contains(content, "\"Read\""));
    }

    @Test
    void removeRule_deletesFromArrayPreservingSiblings() throws IOException {
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings,
            "{\"permissions\": {\"allow\": [\"Read\", \"Bash(git *)\"], \"deny\": [\"Bash(rm *)\"]}}");
        PermissionSettings.removePermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.LOCAL_SETTINGS);
        String content = Files.readString(localSettings);
        assertFalse(Strings.CS.contains(content, "\"Read\""));
        assertTrue(Strings.CS.contains(content, "Bash(git *)"), "other allow entries must survive");
        assertTrue(Strings.CS.contains(content, "Bash(rm *)"), "sibling deny array must survive");
    }

    @Test
    void removeRule_normalizesWildcardWideSyntax() throws IOException {
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"permissions\":{\"allow\":[\"Bash(*)\"]}}");

        PermissionSettings.removePermissionRule(projectDir.toString(), PermissionBehavior.ALLOW,
            "Bash", RuleSource.LOCAL_SETTINGS);

        assertFalse(Strings.CS.contains(Files.readString(localSettings), "Bash(*)"));
    }

    @Test
    void removeRule_missingFile_isNoop() {
        assertDoesNotThrow(() ->
            PermissionSettings.removePermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.LOCAL_SETTINGS));
    }

    @Test
    void persistRemoveRule_missingFile_createsEmptyBehaviorArray() throws IOException {
        PermissionSettings.removePermissionRuleForUpdate(projectDir.toString(),
            PermissionBehavior.ALLOW, "Read", RuleSource.LOCAL_SETTINGS);

        JsonNode root = JsonUtils.readJson(localSettings);
        assertTrue(root.path("permissions").path("allow").isArray());
        assertTrue(root.path("permissions").path("allow").isEmpty());
    }

    @Test
    void removeRule_ruleNotPresent_isNoop() throws IOException {
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"permissions\": {\"allow\": [\"Bash(git *)\"]}}");
        PermissionSettings.removePermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.LOCAL_SETTINGS);
        assertTrue(Strings.CS.contains(Files.readString(localSettings), "Bash(git *)"));
    }

    @Test
    void removeRule_schemaInvalidSource_isNoopLikeValidatedTsDeleteHelper() throws IOException {
        Files.createDirectories(localSettings.getParent());
        String original = "{\"model\":123,\"permissions\":{\"allow\":[\"Read\"]}}";
        Files.writeString(localSettings, original);

        PermissionSettings.removePermissionRule(projectDir.toString(), PermissionBehavior.ALLOW,
            "Read", RuleSource.LOCAL_SETTINGS);

        assertEquals(original, Files.readString(localSettings));
    }

    @Test
    void addRule_rejectsNonEditableTier() {
        assertThrows(IllegalArgumentException.class, () ->
            PermissionSettings.addPermissionRule(projectDir.toString(), PermissionBehavior.ALLOW, "Read", RuleSource.POLICY_SETTINGS));
    }
}
