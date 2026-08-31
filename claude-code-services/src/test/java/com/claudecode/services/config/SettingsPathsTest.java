package com.claudecode.services.config;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.config.EnvUtils;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the shared settings-path resolver. */
class SettingsPathsTest {

    private String originalOsName;
    private Path originalCwd;

    @BeforeEach
    void saveOsName() {
        originalOsName = System.getProperty("os.name");
        originalCwd = CwdState.getOriginalCwd();
    }

    @AfterEach
    void restoreOsName() {
        if (originalOsName == null) {
            System.clearProperty("os.name");
        } else {
            System.setProperty("os.name", originalOsName);
        }
        if (originalCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(originalCwd);
    }

    @Test
    void userSettingsPath_usesTheCurrentClaudeHomeAndCoworkAwareFilename() {
        String filename = EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_USE_COWORK_PLUGINS"))
            ? "cowork_settings.json"
            : "settings.json";

        assertEquals(ClaudePaths.currentClaudeHome().resolve(filename),
            SettingsPaths.userSettingsPath());
    }

    @Test
    void projectSettingsPath_normalizesTheSuppliedWorkingDirectory() {
        String cwd = "settings-paths/../project";

        Path result = SettingsPaths.projectSettingsPath(cwd);

        assertTrue(result.isAbsolute());
        assertEquals(Path.of(cwd).toAbsolutePath().normalize()
                .resolve(".claude").resolve("settings.json"),
            result);
    }

    @Test
    void localSettingsPath_normalizesTheSuppliedWorkingDirectory() {
        String cwd = "settings-paths/../project";

        Path result = SettingsPaths.localSettingsPath(cwd);

        assertTrue(result.isAbsolute());
        assertEquals(Path.of(cwd).toAbsolutePath().normalize()
                .resolve(".claude").resolve("settings.local.json"),
            result);
    }

    @Test
    void sessionSettingsPathsStayAnchoredToOriginalCwdAfterLiveDirectoryChanges() {
        Path sessionRoot = Path.of("session-settings-root").toAbsolutePath().normalize();
        String nestedCwd = sessionRoot.resolve("nested").toString();
        CwdState.setOriginalCwd(sessionRoot);

        assertEquals(sessionRoot.resolve(".claude/settings.json"),
            SettingsPaths.sessionProjectSettingsPath(nestedCwd));
        assertEquals(sessionRoot.resolve(".claude/settings.local.json"),
            SettingsPaths.sessionLocalSettingsPath(nestedCwd));
    }

    @Test
    void policySettingsPath_usesTheMacOsManagedSettingsLocation() {
        System.setProperty("os.name", "Mac OS X");

        assertEquals(Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }

    @Test
    void policySettingsPath_usesTheWindowsManagedSettingsLocation() {
        System.setProperty("os.name", "Windows 11");

        assertEquals(Path.of("C:\\Program Files\\ClaudeCode", "managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }

    @Test
    void policySettingsPath_usesTheLinuxManagedSettingsLocation() {
        System.setProperty("os.name", "Linux");

        assertEquals(Path.of("/etc/claude-code/managed-settings.json"),
            SettingsPaths.policySettingsPath());
    }

    @Test
    void policySettingsDropInDirectory_isSiblingToTheManagedSettingsFile() {
        System.setProperty("os.name", "Mac OS X");

        assertEquals(Path.of("/Library/Application Support/ClaudeCode/managed-settings.d"),
            SettingsPaths.policySettingsDropInDirectory());
    }
}
