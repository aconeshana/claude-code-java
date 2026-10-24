package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that project-scoped {@code permissions.additionalDirectories} are withheld from an
 * untrusted workspace while user settings and trusted workspaces are unaffected.
 */
class AdditionalDirectoriesTrustGateTest {

    @TempDir
    Path tempDir;

    private String originalHome;
    private String originalDirectory;
    private Path home;
    private Path project;

    @BeforeEach
    void redirect() throws IOException {
        originalHome = System.getProperty("user.home");
        originalDirectory = System.getProperty("user.dir");
        home = tempDir.resolve("home");
        project = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project.resolve(".claude"));
        System.setProperty("user.home", home.toString());
        System.setProperty("user.dir", project.toString());
        TrustConfigStore.resetSessionTrustForTesting();
        TrustConfigStore.resetOriginalCwdForTesting();
        TrustConfigStore.setConfigPathForTesting(tempDir.resolve("claude.json"));
        TrustConfigStore.setOriginalCwd(project);
        AdditionalDirectoriesTrustGate.clearTrackingCacheForTesting();
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true, project.toString());
        SettingsSnapshots.invalidateForReload();
    }

    @AfterEach
    void restore() {
        TrustConfigStore.resetSessionTrustForTesting();
        TrustConfigStore.resetOriginalCwdForTesting();
        TrustConfigStore.setConfigPathForTesting(ClaudePaths.GLOBAL_JSON);
        AdditionalDirectoriesTrustGate.clearTrackingCacheForTesting();
        SettingsSources.clearFlagSettings();
        System.setProperty("user.home", originalHome);
        System.setProperty("user.dir", originalDirectory);
        SettingsSources.configureAllowedSettingSources(true, true, true, originalDirectory);
        SettingsSnapshots.invalidateForReload();
    }

    private void write(Path file, String directory) throws IOException {
        Files.writeString(file,
            "{\"permissions\":{\"additionalDirectories\":[\"" + directory + "\"]}}");
        SettingsSnapshots.invalidateForReload();
    }

    private List<String> load() {
        return PermissionSettings.loadAdditionalDirectories(project.toString());
    }

    private void trustParentOnly() throws IOException {
        String key = tempDir.toAbsolutePath().normalize().toString().replace('\\', '/');
        Files.writeString(tempDir.resolve("claude.json"),
            "{\"projects\":{\"" + key + "\":{\"hasTrustDialogAccepted\":true}}}");
    }

    private void git(String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", project.toString()));
        command.addAll(List.of(args));
        assertTrue(ProcessRunner.run(command, project, Duration.ofSeconds(20)).succeeded());
    }

    @Test
    void untrustedWorkspace_dropsProjectSettingsButKeepsUserSettings() throws IOException {
        write(home.resolve(".claude/settings.json"), "/tmp/from-user");
        write(project.resolve(".claude/settings.json"), "/tmp/from-project");

        assertEquals(List.of("/tmp/from-user"), load());
    }

    @Test
    void untrustedNonGitWorkspace_dropsLocalSettings() throws IOException {
        write(project.resolve(".claude/settings.local.json"), "/tmp/from-local");

        assertEquals(List.of(), load());
    }

    @Test
    void trustedWorkspace_keepsProjectAndLocalSettings() throws IOException {
        TrustConfigStore.acceptTrust(project);
        write(project.resolve(".claude/settings.json"), "/tmp/from-project");
        write(project.resolve(".claude/settings.local.json"), "/tmp/from-local");

        assertEquals(List.of("/tmp/from-project", "/tmp/from-local"), load());
    }

    @Test
    void parentTrustWithoutExactEntry_stillDropsProjectSettings() throws IOException {
        trustParentOnly();
        write(project.resolve(".claude/settings.json"), "/tmp/from-project");

        assertEquals(List.of(), load());
    }

    @Test
    void parentTrustedGitWorkspace_keepsUntrackedLocalSettings() throws IOException {
        git("init", "-q");
        trustParentOnly();
        write(project.resolve(".claude/settings.local.json"), "/tmp/from-local");

        assertEquals(List.of("/tmp/from-local"), load());
    }

    @Test
    void parentTrustedGitWorkspace_dropsTrackedLocalSettings() throws IOException {
        git("init", "-q");
        trustParentOnly();
        write(project.resolve(".claude/settings.local.json"), "/tmp/from-local");
        git("add", "-f", ".claude/settings.local.json");

        assertEquals(List.of(), load());
    }

    @Test
    void untrustedGitWorkspace_dropsLocalSettings() throws IOException {
        git("init", "-q");
        write(project.resolve(".claude/settings.local.json"), "/tmp/from-local");

        assertEquals(List.of(), load());
    }

    @Test
    void cwdIsHome_projectSettingsAliasesUserSettingsAndIsKept() throws IOException {
        System.setProperty("user.dir", home.toString());
        SettingsSources.configureAllowedSettingSources(true, true, true, home.toString());
        TrustConfigStore.setOriginalCwd(home);
        write(home.resolve(".claude/settings.json"), "/tmp/from-user");

        assertEquals(List.of("/tmp/from-user"),
            PermissionSettings.loadAdditionalDirectories(home.toString()));
    }
}
