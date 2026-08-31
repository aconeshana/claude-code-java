package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the workspace-facing settings readers extracted from the settings snapshot boundary.
 */
class WorkspaceSettingsTest {

    @TempDir Path workDir;
    @TempDir Path homeDir;

    private String originalHome;
    private String originalDir;

    @BeforeEach
    void redirectSettingsSources() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDir = System.getProperty("user.dir");
        System.setProperty("user.home", homeDir.toString());
        System.setProperty("user.dir", workDir.toString());
        Files.createDirectories(homeDir.resolve(".claude"));
        Files.createDirectories(workDir.resolve(".claude"));
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        SettingsSources.configureAllowedSettingSources(
            List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS, RuleSource.LOCAL_SETTINGS),
            workDir.toString(), true);
    }

    @AfterEach
    void restoreSettingsSources() {
        SettingsSources.clearFlagSettings();
        SettingsSources.clearPluginSettingsBase();
        restoreProperty("user.home", originalHome);
        restoreProperty("user.dir", originalDir);
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalDir == null ? workDir.toString() : originalDir);
    }

    @Test
    void autoMemoryDirectoryUsesTrustedSourcesInPolicyFlagLocalUserOrder() throws Exception {
        write(SettingsPaths.userSettingsPath(), "{\"autoMemoryDirectory\":\"/tmp/user-memory\"}");
        write(SettingsPaths.projectSettingsPath(workDir.toString()),
            "{\"autoMemoryDirectory\":\"/tmp/project-memory\"}");
        write(SettingsPaths.localSettingsPath(workDir.toString()),
            "{\"autoMemoryDirectory\":\"/tmp/local-memory\"}");
        SettingsSources.applyFlagSettings(object("{\"autoMemoryDirectory\":\"/tmp/flag-memory\"}"));

        assertEquals("/tmp/flag-memory" + File.separator,
            WorkspaceSettings.loadAutoMemoryDirectory());
        assertEquals("/tmp/policy-memory", WorkspaceSettings.selectTrustedAutoMemoryDirectory(
            object("{\"autoMemoryDirectory\":\"/tmp/policy-memory\"}"),
            object("{\"autoMemoryDirectory\":\"/tmp/flag-memory\"}"),
            object("{\"autoMemoryDirectory\":\"/tmp/local-memory\"}"),
            object("{\"autoMemoryDirectory\":\"/tmp/user-memory\"}")));
    }

    @Test
    void autoMemoryDirectoryExpandsTildeAndAnExplicitInvalidHigherValueDoesNotFallThrough()
            throws Exception {
        write(SettingsPaths.userSettingsPath(), "{\"autoMemoryDirectory\":\"/tmp/user-memory\"}");
        write(SettingsPaths.localSettingsPath(workDir.toString()),
            "{\"autoMemoryDirectory\":\"~/memory\"}");

        assertEquals(homeDir.resolve("memory").normalize() + File.separator,
            WorkspaceSettings.loadAutoMemoryDirectory());
        assertNull(WorkspaceSettings.validateAutoMemoryDirectory(
            WorkspaceSettings.selectTrustedAutoMemoryDirectory(
                object("{}"), object("{}"),
                object("{\"autoMemoryDirectory\":\"\"}"),
                object("{\"autoMemoryDirectory\":\"/tmp/user-memory\"}"))),
            "an explicitly empty local setting must block lower-priority values");
    }

    @Test
    void autoMemoryDirectoryNeverTrustsProjectSettingsAndRejectsUnsafePaths() throws Exception {
        write(SettingsPaths.projectSettingsPath(workDir.toString()),
            "{\"autoMemoryDirectory\":\"/tmp/project-memory\"}");

        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());

        write(SettingsPaths.localSettingsPath(workDir.toString()),
            "{\"autoMemoryDirectory\":\"relative-memory\"}");
        assertNull(WorkspaceSettings.loadAutoMemoryDirectory());
        assertNull(WorkspaceSettings.validateAutoMemoryDirectory("/"));
    }

    @Test
    void plansDirectoryUsesValidatedEffectiveSettingsAndNeverEscapesTheProject() throws Exception {
        write(SettingsPaths.userSettingsPath(), "{\"plansDirectory\":\"user-plans\"}");
        write(SettingsPaths.projectSettingsPath(workDir.toString()),
            "{\"plansDirectory\":\"project-plans\"}");
        write(SettingsPaths.localSettingsPath(workDir.toString()),
            "{\"plansDirectory\":\"../outside\"}");

        Path fallback = homeDir.resolve(".claude/plans").toAbsolutePath().normalize();
        assertEquals(fallback, WorkspaceSettings.loadPlansDirectory(workDir.toString()));
        assertTrue(Files.isDirectory(fallback));

        SettingsSources.applyFlagSettings(object("{\"plansDirectory\":\"flag-plans\"}"));
        assertEquals(workDir.resolve("flag-plans").toAbsolutePath().normalize(),
            WorkspaceSettings.loadPlansDirectory(workDir.toString()));
    }

    @Test
    void worktreeAndClaudeMdSettingsUseValidatedMergedSnapshotArrays() throws Exception {
        write(SettingsPaths.userSettingsPath(), """
            {"worktree":{"symlinkDirectories":["node_modules","shared"],"baseRef":"fresh"},
             "claudeMdExcludes":["user.md","shared.md",""]}
            """);
        write(SettingsPaths.projectSettingsPath(workDir.toString()), """
            {"worktree":{"symlinkDirectories":["shared","project"],"baseRef":"head"},
             "claudeMdExcludes":["shared.md","project.md"]}
            """);
        write(SettingsPaths.localSettingsPath(workDir.toString()), """
            {"worktree":{"symlinkDirectories":["project","target"]},
             "claudeMdExcludes":["local.md","   "]}
            """);
        SettingsSources.applyFlagSettings(object("""
            {"worktree":{"symlinkDirectories":["target","flag"],"baseRef":"fresh"},
             "claudeMdExcludes":["flag.md","project.md"]}
            """));

        assertEquals(List.of("node_modules", "shared", "project", "target", "flag"),
            WorkspaceSettings.loadWorktreeSymlinkDirectories(workDir.toString()));
        assertEquals("fresh", WorkspaceSettings.loadWorktreeBaseRef(workDir.toString()));
        assertEquals(List.of("user.md", "shared.md", "project.md", "local.md", "flag.md"),
            WorkspaceSettings.loadClaudeMdExcludes(workDir.toString()));
    }

    @Test
    void worktreeSparsePathsAreMergedAndUnsafeEntriesDropped() throws Exception {
        write(SettingsPaths.userSettingsPath(),
            "{\"worktree\":{\"sparsePaths\":[\"src\",\"shared\"]}}");
        write(SettingsPaths.projectSettingsPath(workDir.toString()),
            "{\"worktree\":{\"sparsePaths\":[\"shared\",\"docs/**\",\"../escape\",\"/absolute\"]}}");

        assertEquals(List.of("src", "shared", "docs/**"),
            WorkspaceSettings.loadWorktreeSparsePaths(workDir.toString()));
    }

    @Test
    void invalidSettingsSourceDoesNotLeakWorkspaceValuesIntoTheSnapshot() throws Exception {
        write(SettingsPaths.userSettingsPath(), """
            {"worktree":{"symlinkDirectories":["user"],"baseRef":"head"},
             "claudeMdExcludes":["user.md"]}
            """);
        write(SettingsPaths.localSettingsPath(workDir.toString()), """
            {"worktree":{"symlinkDirectories":["must-not-appear"],"baseRef":"fresh"},
             "claudeMdExcludes":"not-an-array"}
            """);

        assertEquals(List.of("user"),
            WorkspaceSettings.loadWorktreeSymlinkDirectories(workDir.toString()));
        assertEquals("head", WorkspaceSettings.loadWorktreeBaseRef(workDir.toString()));
        assertEquals(List.of("user.md"), WorkspaceSettings.loadClaudeMdExcludes(workDir.toString()));
    }

    private static ObjectNode object(String json) throws IOException {
        return (ObjectNode) JsonUtils.getMapper().readTree(json);
    }

    private static void write(Path path, String json) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, json);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
