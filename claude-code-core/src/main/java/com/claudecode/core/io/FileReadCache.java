package com.claudecode.core.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modification-time-invalidated cache for repeated text-file reads.
 *
 * <ul>
 *   <li>{@code FileReadCache}, including
 *       encoding metadata, a 1000-entry FIFO ceiling, clear/invalidate/stats.</li>
 * </ul>
 */
public final class FileReadCache {
    private static final int MAX_ENTRIES = 1000;
    private final Map<Path, Entry> cache = new LinkedHashMap<>();

    private record Entry(FileTextUtils.TextFile file, long modifiedMillis) {}
    public record Stats(int size, List<Path> entries) {}

    public synchronized FileTextUtils.TextFile read(Path path) throws IOException {
        Path key = path.toAbsolutePath().normalize();
        final long modified;
        try {
            modified = FileUtils.modificationTimeMillis(key);
        } catch (IOException e) {
            cache.remove(key);
            throw e;
        }
        Entry cached = cache.get(key);
        if (cached != null && cached.modifiedMillis() == modified) return cached.file();
        FileTextUtils.TextFile file = FileTextUtils.readWithMetadata(key);
        cache.put(key, new Entry(file, modified));
        while (cache.size() > MAX_ENTRIES) {
            cache.remove(cache.keySet().iterator().next());
        }
        return file;
    }

    public synchronized void clear() { cache.clear(); }
    public synchronized void invalidate(Path path) { cache.remove(path.toAbsolutePath().normalize()); }
    public synchronized Stats stats() { return new Stats(cache.size(), List.copyOf(cache.keySet())); }
}
