package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the parsed-tree cache shared by {@link SettingsTreeReader}:
 * repeated reads of an unchanged file may serve a cached tree, but any change
 * to the file (different content/size, or same size with a newer mtime) must
 * be picked up on the next read — the runtime hot-reload path depends on it.
 */
class SettingsCacheTest {

    @TempDir Path dir;

    @Test
    void rewriteWithDifferentContentIsPickedUp() throws IOException {
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "{\"autoCompactEnabled\": true}");
        assertTrue(RuntimeSettings.loadLayeredBoolean("autoCompactEnabled", false, List.of(f)));

        Files.writeString(f, "{\"autoCompactEnabled\": false}");
        assertFalse(RuntimeSettings.loadLayeredBoolean("autoCompactEnabled", true, List.of(f)),
            "rewritten file must not serve the stale cached tree");
    }

    @Test
    void sameSizeRewriteWithNewerMtimeIsPickedUp() throws IOException {
        Path f = dir.resolve("settings.json");
        // Same byte length ("default" vs "dontAsk") — only mtime distinguishes the writes.
        Files.writeString(f, "{\"permissions\":{\"defaultMode\":\"default\"}}");
        assertEquals("default", PermissionSettings.loadDefaultPermissionMode(List.of(f)));

        Files.writeString(f, "{\"permissions\":{\"defaultMode\":\"dontAsk\"}}");
        // Force a strictly newer mtime in case the FS timestamps both writes identically.
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        assertEquals("dontAsk", PermissionSettings.loadDefaultPermissionMode(List.of(f)),
            "same-size rewrite with a newer mtime must invalidate the cache");
    }

    @Test
    void repeatedReadsOfUnchangedFileStayConsistent() throws IOException {
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "{\"alwaysThinkingEnabled\": true}");
        for (int i = 0; i < 5; i++) {
            assertTrue(RuntimeSettings.loadLayeredBoolean("alwaysThinkingEnabled", false, List.of(f)));
        }
    }

    @Test
    void deletedFileFallsBackToDefault() throws IOException {
        Path f = dir.resolve("settings.json");
        Files.writeString(f, "{\"autoCompactEnabled\": false}");
        assertFalse(RuntimeSettings.loadLayeredBoolean("autoCompactEnabled", true, List.of(f)));

        Files.delete(f);
        assertTrue(RuntimeSettings.loadLayeredBoolean("autoCompactEnabled", true, List.of(f)),
            "a deleted file must not serve its cached tree");
    }

    @Test
    void getSettingsWithSourcesForcesFreshSnapshotEvenWhenStampIsUnchanged() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".claude"));
        Path settings = home.resolve(".claude/settings.json");
        Files.writeString(settings, "{\"language\":\"first\"}");
        FileTime originalStamp = Files.getLastModifiedTime(settings);
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), home.toString());

            assertEquals("first", SettingsSnapshots.withSources(home.toString())
                .path("effective").path("language").asText());
            Files.writeString(settings, "{\"language\":\"second\"}");
            Files.setLastModifiedTime(settings, originalStamp);

            assertEquals("second", SettingsSnapshots.withSources(home.toString())
                .path("effective").path("language").asText(),
                "the explicit snapshot API must not rely on mtime/size cache freshness");
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }

    @Test
    void changingAllowedSourcesInvalidatesSessionSettingsCache() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = dir.resolve("home");
        Path project = dir.resolve("project");
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(home.resolve(".claude/settings.json"), "{\"language\":\"user\"}");
        Files.writeString(project.resolve(".claude/settings.json"), "{\"language\":\"project\"}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", project.toString());

            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), project.toString());
            assertEquals("user", SettingsDiagnostics.getSettingsWithErrors()
                .settings().path("language").asText());

            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.PROJECT_SETTINGS), project.toString());
            assertEquals("project", SettingsDiagnostics.getSettingsWithErrors()
                .settings().path("language").asText(),
                "changing --setting-sources must invalidate the merged session cache");
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? project.toString() : originalDir);
        }
    }

    @Test
    void effectiveSnapshotIsDetachedAndReusedUntilReloadInvalidation() throws Exception {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = dir.resolve("effective-home");
        Files.createDirectories(home.resolve(".claude"));
        Path settings = home.resolve(".claude/settings.json");
        Files.writeString(settings, "{\"language\":\"first\"}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", home.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS), home.toString());

            var first = SettingsSnapshots.effective(home.toString());
            first.put("language", "caller mutation");
            assertEquals("first", SettingsSnapshots.effective(home.toString())
                .path("language").asText(), "callers must receive detached snapshots");

            Files.writeString(settings, "{\"language\":\"second\"}");
            assertEquals("first", SettingsSnapshots.effective(home.toString())
                .path("language").asText(),
                "runtime reads should reuse the merged session snapshot until hot reload invalidates it");

            SettingsSnapshots.invalidateForReload();
            assertEquals("second", SettingsSnapshots.effective(home.toString())
                .path("language").asText());
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? home.toString() : originalDir);
        }
    }
}
