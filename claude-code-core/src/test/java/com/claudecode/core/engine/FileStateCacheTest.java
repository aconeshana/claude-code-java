package com.claudecode.core.engine;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FileStateCacheTest {

    private static FileStateCache.FileState state(String content) {
        return new FileStateCache.FileState(content, 1_000L, null, null, false);
    }

    private static FileStateCache.FileState partial(String content) {
        return new FileStateCache.FileState(content, 1_000L, null, null, true);
    }

    @Test
    void setAndGet_roundTrips() {
        FileStateCache cache = new FileStateCache();
        cache.set("/a.txt", state("hello"));
        assertEquals("hello", cache.get("/a.txt").content());
        assertNull(cache.get("/missing.txt"));
    }

    @Test
    void entryCountEviction_keepsNewest100() {
        FileStateCache cache = new FileStateCache();
        int n = 150;
        for (int i = 0; i < n; i++) {
            cache.set("/f" + i + ".txt", state("x"));
        }
        assertTrue(cache.size() <= 100, "size must be capped at MAX_ENTRIES");
        assertEquals(100, cache.size());
        // Oldest (f0) evicted, newest (f149) retained.
        assertNull(cache.get("/f0.txt"));
        assertEquals("x", cache.get("/f149.txt").content());
    }

    @Test
    void byteEviction_enforces25MbCeiling() {
        FileStateCache cache = new FileStateCache();
        String mb = "a".repeat(1024 * 1024); // 1 MiB of ASCII = 1 MiB bytes
        int n = 40; // 40 MiB total > 25 MiB ceiling
        for (int i = 0; i < n; i++) {
            cache.set("/big" + i + ".txt", state(mb));
        }
        assertTrue(cache.totalBytes() <= 25L * 1024 * 1024,
            "total content bytes must stay within MAX_TOTAL_BYTES, got " + cache.totalBytes());
        assertTrue(cache.size() < n, "byte eviction should have dropped some entries");
        // Newest entries survive (LRU evicts oldest first).
        assertEquals("a".repeat(1024 * 1024), cache.get("/big" + (n - 1) + ".txt").content());
    }

    @Test
    void singleOversizedEntry_isKept_notEvicted() {
        FileStateCache cache = new FileStateCache();
        String huge = "a".repeat(30 * 1024 * 1024); // 30 MiB, exceeds ceiling alone
        cache.set("/huge.txt", state(huge));
        assertEquals(1, cache.size());
        assertEquals(huge, cache.get("/huge.txt").content());
        assertTrue(cache.totalBytes() > 25L * 1024 * 1024,
            "a single oversized just-added entry is retained (size()>1 guard)");
    }

    @Test
    void overwrite_updatesByteAccounting() {
        FileStateCache cache = new FileStateCache();
        cache.set("/k.txt", state("a".repeat(100)));
        assertEquals(100, cache.totalBytes());
        cache.set("/k.txt", state("b".repeat(200)));
        assertEquals(1, cache.size());
        assertEquals(200, cache.totalBytes());
    }

    @Test
    void remove_adjustsByteAccounting() {
        FileStateCache cache = new FileStateCache();
        cache.set("/k.txt", state("a".repeat(100)));
        cache.remove("/k.txt");
        assertEquals(0, cache.totalBytes());
        assertEquals(0, cache.size());
    }

    @Test
    void clear_resetsEverything() {
        FileStateCache cache = new FileStateCache();
        cache.set("/k.txt", state("a".repeat(100)));
        cache.clear();
        assertEquals(0, cache.totalBytes());
        assertEquals(0, cache.size());
    }

    @Test
    void mergeFrom_keepsNewerTimestampAndAccountsBytes() {
        FileStateCache a = new FileStateCache();
        a.set("/shared.txt", new FileStateCache.FileState("old", 1_000L, null, null, false));
        // b carries a newer timestamp for the same path — merge must win.
        FileStateCache b = new FileStateCache();
        b.set("/shared.txt",
            new FileStateCache.FileState("newer内容", 2_000L, null, null, false));
        a.mergeFrom(b);
        assertEquals("newer内容", a.get("/shared.txt").content());
        // After merge the single shared entry's bytes reflect the merged value.
        assertEquals(a.get("/shared.txt").content().getBytes(UTF_8).length, a.totalBytes());
    }

    @Test
    void copy_preservesEntriesAndByteAccounting() {
        FileStateCache cache = new FileStateCache();
        cache.set("/a.txt", state("alpha"));
        cache.set("/b.txt", state("bravo"));
        FileStateCache clone = cache.copy();
        assertEquals(cache.size(), clone.size());
        assertEquals(cache.totalBytes(), clone.totalBytes());
        assertEquals("alpha", clone.get("/a.txt").content());
        // Mutating the clone does not affect the original.
        clone.set("/c.txt", state("charlie"));
        assertEquals(2, cache.size());
        assertEquals(3, clone.size());
    }

    @Test
    void isPartialView_marksSyntheticView() {
// matches NestedMemoryAttachmentProvider: auto-injected (stripped) memory
        // files store isPartialView=true so Edit/Write still require a real Read.
        FileStateCache cache = new FileStateCache();
        cache.set("/CLAUDE.md", partial("stripped body"));
        assertTrue(cache.get("/CLAUDE.md").isPartialView());
        cache.set("/real.txt", state("full read"));
        assertFalse(cache.get("/real.txt").isPartialView());
    }

    @Test
    void entries_returnsSnapshotCopy() {
        FileStateCache cache = new FileStateCache();
        cache.set("/a.txt", state("alpha"));
        Map<String, FileStateCache.FileState> snap = cache.entries();
        cache.set("/b.txt", state("bravo"));
        assertEquals(1, snap.size()); // snapshot is decoupled from live cache
        assertEquals(2, cache.size());
    }
}
