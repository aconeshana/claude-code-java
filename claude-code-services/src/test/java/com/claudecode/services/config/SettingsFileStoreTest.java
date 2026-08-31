package com.claudecode.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.RuleSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

class SettingsFileStoreTest {

    @Test
    void concurrentMutationsOnTheSamePathDoNotLoseUpdates() throws Exception {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\"count\":0}");
        int workers = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    SettingsFileStore.mutate(file,
                        root -> root.put("count", root.path("count").asInt() + 1));
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }));
        }

        start.countDown();
        for (Thread thread : threads) thread.join();

        assertEquals(workers, JsonUtils.readJson(file).path("count").asInt());
    }

    @TempDir
    Path tmp;

    @BeforeEach
    void clearInternalWrites() {
        InternalWrites.clearInternalWrites();
    }

    @Test
    void mutateCreatesParentsAndPreservesUnrelatedKeys() throws IOException {
        Path file = tmp.resolve("nested/settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"keep\":1}");

        SettingsFileStore.mutate(file, root -> root.put("enabled", true));

        var root = JsonUtils.readJson(file);
        assertEquals(1, root.path("keep").asInt());
        assertTrue(root.path("enabled").asBoolean());
        assertTrue(Strings.CS.endsWith(Files.readString(file), "\n"),
                "settings writes must retain TS's final LF");
        assertTrue(InternalWrites.consumeInternalWrite(file, 5_000));
    }

    @Test
    void mutateCreatesMissingSettingsParentLikeTsUpdater() throws IOException {
        Path file = tmp.resolve("new-project/.claude/settings.json");

        SettingsFileStore.mutate(file, root -> root.put("enabled", true));

        assertTrue(Files.isDirectory(file.getParent()));
        assertTrue(JsonUtils.readJson(file).path("enabled").asBoolean());
    }

    @Test
    void mutateTreatsBlankAndBomOnlyFilesAsEmptyObjectsLikeTsUpdater() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "\uFEFF  \n");

        SettingsFileStore.mutate(file, root -> root.put("enabled", true));

        assertTrue(JsonUtils.readJson(file).path("enabled").asBoolean());
        assertTrue(InternalWrites.consumeInternalWrite(file, 5_000));
    }

    @Test
    void writeInvalidatesSettingsTreeCache() throws IOException {
        String originalHome = System.getProperty("user.home");
        String originalDir = System.getProperty("user.dir");
        Path home = tmp.resolve("cache-home");
        Path cwd = tmp.resolve("cache-cwd");
        Path file = home.resolve(".claude/settings.json");
        Files.createDirectories(file.getParent());
        Files.createDirectories(cwd);
        Files.writeString(file, "{\"language\":\"before\"}");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("user.dir", cwd.toString());
            SettingsSources.configureAllowedSettingSources(
                List.of(RuleSource.USER_SETTINGS),
                cwd.toString());
            assertEquals("before", SettingsSources.settingsForSource(
                RuleSource.USER_SETTINGS, cwd.toString())
                .path("language").asText());

            SettingsFileStore.mutate(file, root -> root.put("language", "after"));

            assertEquals("after", SettingsSources.settingsForSource(
                RuleSource.USER_SETTINGS, cwd.toString())
                .path("language").asText());
        } finally {
            if (originalHome != null) System.setProperty("user.home", originalHome);
            if (originalDir != null) System.setProperty("user.dir", originalDir);
            SettingsSources.configureAllowedSettingSources(true, true, true,
                originalDir == null ? cwd.toString() : originalDir);
        }
    }

    @Test
    void mutateRejectsMalformedJsonWithoutReplacingIt() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{bad");

        assertThrows(IOException.class,
                () -> SettingsFileStore.mutate(file, root -> root.put("enabled", true)));

        assertEquals("{bad", Files.readString(file));
        assertFalse(InternalWrites.consumeInternalWrite(file, 5_000));
    }

    @Test
    void mutateRejectsJsonNullRootLikeTsUpdater() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "null\n");

        assertThrows(IOException.class,
            () -> SettingsFileStore.mutate(file, root -> root.put("enabled", true)));

        assertEquals("null\n", Files.readString(file));
        assertFalse(InternalWrites.consumeInternalWrite(file, 5_000));
    }

    @Test
    void mutatePreservesAnExistingArrayRootLikeTsMerge() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "[\n  1\n]\n");

        SettingsFileStore.mutate(file, root -> root.put("enabled", true));

        assertEquals("[ 1 ]\n", Files.readString(file).replace("\n", " ")
            .replaceAll("\\s+", " ").trim() + "\n");
        assertTrue(JsonUtils.readJson(file).isArray());
    }

    @Test
    void mutateRejectsAnExistingNonFilePathInsteadOfTreatingItAsMissing() throws IOException {
        Path path = tmp.resolve("settings.json");
        Files.createDirectory(path);

        assertThrows(IOException.class,
                () -> SettingsFileStore.mutate(path, root -> root.put("enabled", true)));

        assertTrue(Files.isDirectory(path));
        assertFalse(InternalWrites.consumeInternalWrite(path, 5_000));
    }

    @Test
    void mutateWritesThroughABrokenSymlinkLikeNode() throws IOException {
        Path link = tmp.resolve("settings.json");
        Path missingTarget = tmp.resolve("missing-target.json");
        Files.createSymbolicLink(link, missingTarget);

        SettingsFileStore.mutate(link, root -> root.put("enabled", true));

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.exists(missingTarget));
        assertTrue(JsonUtils.readJson(missingTarget).path("enabled").asBoolean());
        assertTrue(InternalWrites.consumeInternalWrite(link, 5_000));
    }

    @Test
    void mutateIfExistsDoesNotCreateMissingFile() throws IOException {
        Path file = tmp.resolve("missing/settings.json");

        boolean changed = SettingsFileStore.mutateIfExists(file, root -> {
            root.put("enabled", true);
            return true;
        });

        assertFalse(changed);
        assertFalse(Files.exists(file));
    }

    @Test
    void mutateIfExistsSkipsWriteWhenEditorReportsNoChange() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\"keep\":1}");
        String before = Files.readString(file);

        boolean changed = SettingsFileStore.mutateIfExists(file, _ -> false);

        assertFalse(changed);
        assertEquals(before, Files.readString(file));
        assertFalse(InternalWrites.consumeInternalWrite(file, 5_000));
    }

    @Test
    void mutateSkipsByteIdenticalObjectUpdates() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\"effortLevel\":\"high\"}\n");

        SettingsFileStore.mutate(file, root -> root.put("effortLevel", "high"));

        assertEquals("{\"effortLevel\":\"high\"}\n", Files.readString(file));
        assertFalse(InternalWrites.consumeInternalWrite(file, 5_000));
    }
}
