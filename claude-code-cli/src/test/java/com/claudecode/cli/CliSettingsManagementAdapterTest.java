package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.InternalWrites;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.core.serialization.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSettingsManagementAdapterTest {
    @Test void typedPreferencesDelegateToExistingStores(@TempDir Path dir) {
        Path global = dir.resolve("claude.json");
        Path settings = dir.resolve("settings.json");
        CliSettingsManagementAdapter adapter =
            new CliSettingsManagementAdapter(global, settings);

        adapter.preferences().saveTheme("light");
        adapter.preferences().saveEffortLevel("high");
        adapter.preferences().saveAdvisorModel("opus");
        adapter.preferences().saveCopyFullResponse(true);
        adapter.preferences().incrementBtwUseCount();
        GlobalConfigStore.set(global, "primaryApiKey", "secret");

        assertEquals("light", adapter.preferences().theme());
        assertEquals("high", adapter.preferences().effortLevel());
        assertEquals("opus", adapter.preferences().advisorModel().orElseThrow());
        assertTrue(adapter.preferences().copyFullResponse());
        assertTrue(adapter.preferences().hasStoredApiKey());
        assertTrue(InternalWrites.consumeInternalWrite(settings, 5_000));
    }

    @Test void sandboxMutationsStayLocalDeduplicateAndGitignore(@TempDir Path dir)
            throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = dir.resolve("home");
        Files.createDirectories(home);
        System.setProperty("user.home", home.toString());
        try {
        Path global = dir.resolve("claude.json");
        Path settings = dir.resolve("settings.json");
        assertEquals(0, new ProcessBuilder("git", "init", "--quiet")
            .directory(dir.toFile()).start().waitFor());
        CliSettingsManagementAdapter adapter =
            new CliSettingsManagementAdapter(global, settings);

        adapter.sandbox().saveSettings(dir.toString(), true, true, false);
        adapter.sandbox().addExcludedCommand(dir.toString(), "npm test");
        adapter.sandbox().addExcludedCommand(dir.toString(), "npm test");
        adapter.sandbox().saveAdditionalDirectory(dir.toString(), dir.resolve("extra").toString());

        Path local = SettingsPaths.sessionLocalSettingsPath(dir.toString());
        String json = Files.readString(local);
        assertTrue(Strings.CS.contains(json, "\"enabled\" : true"));
        assertEquals(1, occurrences(json, "npm test"));
        assertTrue(Strings.CS.contains(json, "additionalDirectories"));
        assertTrue(Strings.CS.contains(Files.readString(home.resolve(".config/git/ignore")),
            "**/.claude/settings.local.json"));
        assertFalse(Files.exists(settings));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test void switchModelsOnFlagLandsInConfiguredUserSettingsNotGlobalConfig(
            @TempDir Path dir) throws Exception {
        Path global = dir.resolve("claude.json");
        Path settings = dir.resolve("settings.json");
        CliSettingsManagementAdapter adapter =
            new CliSettingsManagementAdapter(global, settings);

        adapter.configuration().save(dir.toString(), "switchModelsOnFlag", "false");

        assertTrue(InternalWrites.consumeInternalWrite(settings, 5_000));
        assertTrue(Strings.CS.contains(Files.readString(settings),
            "\"switchModelsOnFlag\" : false"));
        assertFalse(Files.exists(global),
            "the refusal toggle is a settings.json key, not a ~/.claude.json key");
    }

    @Test void released197RowsUseTheirRealStoresAndNestedShapes(@TempDir Path dir)
            throws Exception {
        Path global = dir.resolve("claude.json");
        Path settings = dir.resolve("settings.json");
        CliSettingsManagementAdapter adapter =
            new CliSettingsManagementAdapter(global, settings);

        adapter.configuration().save(dir.toString(), "awaySummaryEnabled", "false");
        adapter.configuration().save(dir.toString(), "defaultPermissionMode", "auto");
        adapter.configuration().save(dir.toString(), "worktreeBaseRef", "head");
        adapter.configuration().save(dir.toString(), "language", "Chinese");
        adapter.configuration().save(dir.toString(), "preferredNotifChannel", "ghostty");
        adapter.configuration().save(dir.toString(), "externalEditorContext", "true");

        var user = JsonUtils.getMapper().readTree(settings.toFile());
        assertFalse(user.path("awaySummaryEnabled").asBoolean(true));
        assertEquals("auto", user.path("permissions").path("defaultMode").asText());
        assertEquals("head", user.path("worktree").path("baseRef").asText());
        assertEquals("Chinese", user.path("language").asText());
        assertEquals("ghostty", GlobalConfigStore.getString(
            global, "preferredNotifChannel", null));
        assertTrue(GlobalConfigStore.getBoolean(global, "externalEditorContext", false));
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(value, at)) >= 0; at += value.length()) count++;
        return count;
    }
}
