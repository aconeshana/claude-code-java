package com.claudecode.services.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


class SettingsPlansDirectoryTest {

    @TempDir Path tempDir;

    @Test
    void missingSettingUsesClaudeConfigPlansDirectory() {
        Path fallback = tempDir.resolve("config/plans");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            tempDir.resolve("project"), null, fallback);

        assertEquals(fallback.toAbsolutePath().normalize(), result);
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void relativeSettingResolvesInsideProjectRoot() {
        Path project = tempDir.resolve("project");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            project, ".claude/custom-plans", tempDir.resolve("config/plans"));

        assertEquals(project.resolve(".claude/custom-plans").toAbsolutePath().normalize(), result);
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void projectRootItselfIsAllowed() {
        Path project = tempDir.resolve("project");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            project, ".", tempDir.resolve("config/plans"));

        assertEquals(project.toAbsolutePath().normalize(), result);
    }

    @Test
    void whitespaceSettingIsARealPathComponentLikeNodeResolve() {
        Path project = tempDir.resolve("project");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            project, "  ", tempDir.resolve("config/plans"));

        assertEquals(project.resolve("  ").toAbsolutePath().normalize(), result);
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void traversalOutsideProjectFallsBack() {
        Path fallback = tempDir.resolve("config/plans");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            tempDir.resolve("project"), "../outside", fallback);

        assertEquals(fallback.toAbsolutePath().normalize(), result);
        assertTrue(Files.isDirectory(result));
    }

    @Test
    void absolutePathOutsideProjectFallsBack() {
        Path fallback = tempDir.resolve("config/plans");

        Path result = WorkspaceSettings.resolvePlansDirectory(
            tempDir.resolve("project"), tempDir.resolve("elsewhere").toString(), fallback);

        assertEquals(fallback.toAbsolutePath().normalize(), result);
    }

    @Test
    void laterSettingsTierWins() throws Exception {
        Path project = tempDir.resolve("project");
        Path user = tempDir.resolve("user.json");
        Path projectSettings = tempDir.resolve("project.json");
        Path local = tempDir.resolve("local.json");
        Files.writeString(user, "{\"plansDirectory\":\"user-plans\"}");
        Files.writeString(projectSettings, "{\"plansDirectory\":\"project-plans\"}");
        Files.writeString(local, "{\"plansDirectory\":\"local-plans\"}");

        Path result = WorkspaceSettings.loadPlansDirectory(
            project.toString(), List.of(user, projectSettings, local), tempDir.resolve("config/plans"));

        assertEquals(project.resolve("local-plans").toAbsolutePath().normalize(), result);
    }
}
