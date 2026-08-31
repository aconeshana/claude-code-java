package com.claudecode.services.config;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class GlobalConfigStoreTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve(".claude.json");
    }

    @Test
    void getBooleanReturnsDefaultForMissingFile() {
        assertTrue(GlobalConfigStore.getBoolean(file(), "verbose", true));
        assertFalse(GlobalConfigStore.getBoolean(file(), "verbose", false));
    }

    @Test
    void setBooleanThenGetBooleanRoundTrips() {
        GlobalConfigStore.set(file(), "verbose", true);
        assertTrue(GlobalConfigStore.getBoolean(file(), "verbose", false));

        GlobalConfigStore.set(file(), "verbose", false);
        assertFalse(GlobalConfigStore.getBoolean(file(), "verbose", true));
    }

    @Test
    void setStringThenGetStringRoundTrips() {
        GlobalConfigStore.set(file(), "theme", "dark");
        assertEquals("dark", GlobalConfigStore.getString(file(), "theme", "auto"));
    }

    @Test
    void setNullRemovesKey() {
        GlobalConfigStore.set(file(), "theme", "dark");
        GlobalConfigStore.set(file(), "theme", null);
        assertEquals("auto", GlobalConfigStore.getString(file(), "theme", "auto"));
    }

    @Test
    void setPreservesUnrelatedKeys() {
        GlobalConfigStore.set(file(), "theme", "dark");
        GlobalConfigStore.set(file(), "verbose", true);

        assertEquals("dark", GlobalConfigStore.getString(file(), "theme", "auto"));
        assertTrue(GlobalConfigStore.getBoolean(file(), "verbose", false));
    }

    @Test
    void setCreatesParentDirectories() throws IOException {
        Path nested = tempDir.resolve("nested/dir/.claude.json");
        GlobalConfigStore.set(nested, "verbose", true);
        assertTrue(Files.exists(nested));
    }

    @Test
    void setSkipsWriteWhenValueIsUnchanged() {
        InternalWrites.clearInternalWrites();
        GlobalConfigStore.set(file(), "theme", "dark");
        assertTrue(InternalWrites.consumeInternalWrite(file(), 5_000));

        GlobalConfigStore.set(file(), "theme", "dark");

        assertFalse(InternalWrites.consumeInternalWrite(file(), 5_000));
    }

    @Test
    void malformedFileFallsBackToDefault() throws IOException {
        Files.writeString(file(), "not json");
        assertEquals("auto", GlobalConfigStore.getString(file(), "theme", "auto"));
    }

    @Test
    void skillUsageScores_applySevenDayHalfLifeAndTenPercentFloor() throws IOException {
        long now = 2_000_000_000_000L;
        Files.writeString(file(), """
            {
              "skillUsage": {
                "fresh": {"usageCount": 8, "lastUsedAt": 2000000000000},
                "week-old": {"usageCount": 8, "lastUsedAt": 1999395200000},
                "very-old": {"usageCount": 8, "lastUsedAt": 1900000000000},
                "invalid": {"usageCount": "many", "lastUsedAt": 1}
              }
            }
            """);

        var scores = GlobalConfigStore.getSkillUsageScores(file(), now);

        assertEquals(8.0, scores.get("fresh"), 0.000_001);
        assertEquals(4.0, scores.get("week-old"), 0.000_001);
        assertEquals(0.8, scores.get("very-old"), 0.000_001);
        assertFalse(scores.containsKey("invalid"));
    }

    @Test
    void concurrentDistinctKeyWrites_doNotClobber() throws Exception {
        // The WRITE_MONITOR lock + atomic rename must serialize concurrent read-modify-write
        // so 30 distinct top-level keys (the distinct-project-entry case that the trust store
        // collapses into one entry under originalCwd) all survive with no clobbering/corruption.
        Path f = file();
        int n = 30;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(ex.submit(() -> {
                GlobalConfigStore.set(f, "k" + idx, idx);
                return null;
            }));
        }
        for (Future<?> fut : futures) {
            fut.get();
        }
        ex.shutdown();
        JsonNode root =
            JsonUtils.readJson(f);
        assertNotNull(root, "config file must be valid JSON after concurrent writes");
        assertEquals(n, root.size(), "all concurrent distinct-key writes must be present");
        for (int i = 0; i < n; i++) {
            assertTrue(root.has("k" + i), "key k" + i + " must be present");
        }
    }
}
