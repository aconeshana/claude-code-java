package com.claudecode.services.hooks;

import com.claudecode.permissions.RuleSource;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.core.state.CwdState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

class HooksConfigManagerPathTest {

    @TempDir
    Path tempDir;

    @Test
    void userSettingsPathUsesClaudeConfigHome() {
        assertEquals(SettingsPaths.userSettingsPath(), HooksConfigManager.userSettingsPath());
    }

    @Test
    void invalidSettingsSourceDoesNotExposeRawHooks() throws Exception {
        Path settingsPath = tempDir.resolve(".claude/settings.json");
        Files.createDirectories(settingsPath.getParent());
        Files.writeString(settingsPath, """
            {
              "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "echo hidden"}]}]},
              "cleanupPeriodDays": "not-a-number"
            }
            """);

        Path previousCwd = CwdState.getOriginalCwd();
        try {
            CwdState.clearForTesting();
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS), tempDir.toString(), false);

            assertTrue(HooksConfigManager.getAllHooks(tempDir.toString()).stream()
                .noneMatch(hook -> hook.command() instanceof BashCommandHook
                    &&Strings.CS.equals( ((BashCommandHook) hook.command()).command(), "echo hidden")));
        } finally {
            if (previousCwd == null) CwdState.clearForTesting();
            else CwdState.setOriginalCwd(previousCwd);
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS, RuleSource.PROJECT_SETTINGS,
                    RuleSource.LOCAL_SETTINGS), tempDir.toString(), false);
        }
    }
}
