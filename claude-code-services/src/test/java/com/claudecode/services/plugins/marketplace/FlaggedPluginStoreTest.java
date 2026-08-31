package com.claudecode.services.plugins.marketplace;

import com.claudecode.core.serialization.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlaggedPluginStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void addMarkSeenAndDismissUseAtomicOwnerOnlyFile() throws Exception {
        Path file = tempDir.resolve("flagged-plugins.json");
        FlaggedPluginStore store = new FlaggedPluginStore(file);

        store.add("demo@test-market");
        FlaggedPluginStore.Entry added = store.load().get("demo@test-market");
        assertNotNull(added);
        assertNotNull(added.flaggedAt());
        store.markSeen(List.of("demo@test-market"));
        assertNotNull(store.load().get("demo@test-market").seenAt());
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }

        store.remove("demo@test-market");
        assertTrue(store.load().isEmpty());
    }

    @Test
    void entriesExpireFortyEightHoursAfterFirstSeen() throws Exception {
        Path file = tempDir.resolve("flagged-plugins.json");
        Files.writeString(file, """
            {"plugins":{"old@test":{"flaggedAt":"2026-01-01T00:00:00Z","seenAt":%s},
                        "unseen@test":{"flaggedAt":"2026-01-01T00:00:00Z"}}}
            """.formatted(JsonUtils.toJson(
                Instant.now().minus(49, ChronoUnit.HOURS).toString())));

        var loaded = new FlaggedPluginStore(file).load();

        assertFalse(loaded.containsKey("old@test"));
        assertTrue(loaded.containsKey("unseen@test"));
    }
}
