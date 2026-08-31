package com.claudecode.services.config;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.core.state.CwdState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the "absent vs malformed" contract for the extracted settings readers.
 *
 * <p>Missing files must degrade quietly to empty defaults — a fresh install
 * with no  yet is normal. But a file that
 * <em>exists</em> and fails to parse must raise {@link SettingsParseException}
 * so the hot-reload orchestrator can preserve the previous in-memory state
 * instead of nuking it (a user mid-edit typing {@code { broken} should not
 * lose their live rules).
 *
 * <p>All tests inject paths directly — no {@code user.home} override required.
 */
class SettingsMalformedTest {

    @TempDir Path tempDir;

    private Path fakeSettings;

    @BeforeEach
    void setup() throws IOException {
        Path claudeDir = tempDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        fakeSettings = claudeDir.resolve("settings.json");
    }

    @Test
    void permissions_missingFile_returnsEmpty() {
        assertTrue(PermissionSettings.loadPermissionRulesFromFile(
                fakeSettings, RuleSource.USER_SETTINGS).isEmpty(),
            "absent settings file should degrade to empty list, not throw");
    }

    @Test
    void permissions_malformedJson_throwsParseException() throws IOException {
        Files.writeString(fakeSettings, "{ this is not json");

        SettingsParseException ex = assertThrows(SettingsParseException.class,
            () -> PermissionSettings.loadPermissionRulesFromFile(fakeSettings, RuleSource.USER_SETTINGS),
            "malformed JSON must throw so the reload path can keep prior state");
        assertEquals(fakeSettings.toAbsolutePath().normalize(), ex.path().toAbsolutePath().normalize());
    }

    @Test
    void permissions_schemaInvalidSource_contributesNoRules() throws IOException {

        // array does not make an otherwise invalid settings source executable.
        Files.writeString(fakeSettings,
            "{\"model\":123,\"permissions\":{\"allow\":[\"Read(*)\"]}}");

        assertTrue(PermissionSettings.loadPermissionRulesFromFile(
                fakeSettings, RuleSource.USER_SETTINGS).isEmpty());
    }

    @Test
    void scalarReaders_ignoreSchemaInvalidSource() throws IOException {
        Files.writeString(fakeSettings,
            "{\"model\":123,\"language\":\"should-not-apply\"}");

        assertNull(RuntimeSettings.loadLayeredString(
            "language", List.of(fakeSettings)));
    }

    @Test
    void loadPermissionRules_malformedUserFile_throwsBeforeEmptyingProjectAndLocal()
            throws IOException {
        // If user settings blows up, we abort — we do NOT silently return
        // "just project + local rules" because that would still drop the
        // user's disk rules on a transient edit.
        Files.writeString(fakeSettings, "{ oops");

        assertThrows(SettingsParseException.class,
            () -> PermissionSettings.loadPermissionRulesFromFile(fakeSettings, RuleSource.USER_SETTINGS));
    }

    @Test
    void executionPermissionLoad_skipsMalformedUserKeepsProject() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path originalCwd = CwdState.getOriginalCwd();
        CwdState.clearForTesting();
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"), "{ oops");
        Files.writeString(project.resolve(".claude/settings.json"),
            "{\"permissions\":{\"allow\":[\"Read(*)\"]}}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS),
                project.toString());

            var rules = PermissionSettings.loadPermissionRulesForExecution(project.toString());
            assertEquals(List.of(RuleSource.PROJECT_SETTINGS),
                rules.stream().map(PermissionRule::source).toList());
        } finally {
            SettingsSources.clearFlagSettings();
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            if (originalCwd == null) CwdState.clearForTesting();
            else CwdState.setOriginalCwd(originalCwd);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void hooks_missingFile_returnsEmpty() {
        assertSame(HooksSettings.EMPTY, HookSettings.loadHooksSettings(fakeSettings),
            "absent settings file → hooks EMPTY, not throw");
    }

    @Test
    void hooks_malformedJson_throwsParseException() throws IOException {
        Files.writeString(fakeSettings, "{ nope");
        assertThrows(SettingsParseException.class,
            () -> HookSettings.loadHooksSettings(fakeSettings));
    }

    @Test
    void hooks_schemaInvalidSource_contributesNoHooks() throws IOException {
        Files.writeString(fakeSettings,
            "{\"model\":123,\"hooks\":{\"Stop\":[]}}");

        assertSame(HooksSettings.EMPTY, HookSettings.loadHooksSettings(fakeSettings));
    }
}
