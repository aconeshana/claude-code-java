package com.claudecode.core.engine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Per-session cache of what the model has seen of each file — backs the read-before-write safety
 * check shared by FileRead/FileWrite/FileEdit.
 */
public final class FileStateCache {

    private static final int MAX_ENTRIES = 100;


    private static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private final Object lock = new Object();
    private long totalBytes = 0;

    /** Latest explicit Read range per path, kept session-scoped with this cache. */
    private final Map<String, ReadRange> latestReadRanges = new LinkedHashMap<>();

    private final Map<String, FileState> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileState> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    /**
     * What the model has seen of a file as of its last successful Read (or
     * Write/Edit, which also count as "the model now knows this content").
     *
     * @param content       the raw file content at read time (used for the
     *                      full-read content-equality fallback below)
     * @param timestampMs   the file's on-disk mtime (epoch millis) at read
     *                      time — NOT wall-clock read time; staleness is
     *                      "has the file's mtime moved past this", not "how
     *                      long ago did I read it"
     * @param offset        1-indexed start line of a partial read, or
     *                      {@code null} for a full read
     * @param limit         line count of a partial read, or {@code null} for
     *                      a full read
     * @param isPartialView {@code true} when the model was shown content that
     *                      doesn't match disk (e.g. auto-injected CLAUDE.md
     *                      with stripped frontmatter) — an explicit Read is
     *                      still required before Edit/Write. Set {@code true}
     *                      by {@code NestedMemoryAttachmentProvider} for such
     *                      auto-injected memory files; a real {@code Read}
     *                      (see {@code FileReadTool}) is always a full view and
     *                      therefore stores {@code false} here.
     */
    public record FileState(
        String content,
        long timestampMs,
        Integer offset,
        Integer limit,
        boolean isPartialView
    ) {
        /** A full (non-partial) read is eligible for the content-equality staleness fallback. */
        public boolean isFullRead() {
            return offset == null && limit == null;
        }
    }

    public FileState get(String absolutePath) {
        return cache.get(normalize(absolutePath));
    }

    public void set(String absolutePath, FileState state) {
        synchronized (lock) {
            String key = normalize(absolutePath);
            FileState old = cache.get(key);
            if (old != null) totalBytes -= byteLen(old);
            cache.put(key, state);
            latestReadRanges.remove(key);
            totalBytes += byteLen(state);
            enforceLimits();
        }
    }

    /** Records the latest successful Read for mtime/range deduplication. */
    public void recordReadRange(String absolutePath, int offset, Integer limit, long timestampMs) {
        synchronized (lock) {
            String key = normalize(absolutePath);
            if (cache.containsKey(key)) {
                latestReadRanges.put(key, new ReadRange(offset, limit, timestampMs));
            }
        }
    }

    /** True only for the latest successful Read of this path in this session. */
    public boolean matchesLatestReadRange(String absolutePath, int offset,
                                          Integer limit, long timestampMs) {
        synchronized (lock) {
            ReadRange range = latestReadRanges.get(normalize(absolutePath));
            return range != null && range.offset() == offset
                && Objects.equals(range.limit(), limit)
                && range.timestampMs() == timestampMs;
        }
    }

    public void remove(String absolutePath) {
        synchronized (lock) {
            FileState removed = cache.remove(normalize(absolutePath));
            latestReadRanges.remove(normalize(absolutePath));
            if (removed != null) totalBytes -= byteLen(removed);
        }
    }

    public void clear() {
        synchronized (lock) {
            cache.clear();
            latestReadRanges.clear();
            totalBytes = 0;
        }
    }

    /**
     * Evicts least-recently-used entries until both {@link #MAX_ENTRIES} and
     * {@link #MAX_TOTAL_BYTES} are satisfied. Callers must hold {@link #lock}.
     * The {@code size > 1} guard keeps a single (possibly oversized) just-added
     * entry from evicting itself.
     */
    private void enforceLimits() {
        while (cache.size() > 1
               && (cache.size() > MAX_ENTRIES || totalBytes > MAX_TOTAL_BYTES)) {
            Iterator<String> it = cache.keySet().iterator();
            if (!it.hasNext()) break;
            String removedKey = it.next();
            FileState removed = cache.remove(removedKey);
            latestReadRanges.remove(removedKey);
            if (removed != null) totalBytes -= byteLen(removed);
        }
    }

    private static long byteLen(FileState s) {
        return s.content() == null ? 0 : s.content().getBytes(StandardCharsets.UTF_8).length;
    }

    private record ReadRange(int offset, Integer limit, long timestampMs) {}

    public Set<String> getCachedPaths() {
        synchronized (cache) {
            return Set.copyOf(cache.keySet());
        }
    }

    public int size() {
        return cache.size();
    }

    /** Test-only: current summed content byte count (for assertions). */
    long totalBytes() {
        synchronized (lock) {
            return totalBytes;
        }
    }


    public Map<String, FileState> entries() {
        synchronized (cache) {
            return new LinkedHashMap<>(cache);
        }
    }

    /**
     * Merges another cache's entries into this one in place.
     */
    public void mergeFrom(FileStateCache other) {
        if (other == null) return;
        synchronized (lock) {
            for (Map.Entry<String, FileState> e : other.entries().entrySet()) {
                String key = e.getKey();
                FileState incoming = e.getValue();
                FileState existing = cache.get(key);
                if (existing == null || incoming.timestampMs() > existing.timestampMs()) {
                    if (existing != null) totalBytes -= byteLen(existing);
                    cache.put(key, incoming);
                    totalBytes += byteLen(incoming);
                }
            }
            enforceLimits();
        }
    }

    /**
     * Deep-copies all entries into a new cache.
     */
    public FileStateCache copy() {
        FileStateCache clone = new FileStateCache();
        synchronized (lock) {
            clone.cache.putAll(this.entries());
            for (FileState s : clone.cache.values()) {
                clone.totalBytes += byteLen(s);
            }
        }
        return clone;
    }


    public static FileStateCache createFileStateCacheWithSizeLimit() {
        return new FileStateCache();
    }

    private static String normalize(String absolutePath) {
        return Path.of(absolutePath).normalize().toString();
    }
}
