package com.claudecode.services.config;


import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks timestamps of in-process settings-file writes so the file-system watcher in {@link
 * SettingsHotReloader} can ignore its own echoes.
 */
public final class InternalWrites {

    private static final ConcurrentHashMap<String, Long> TIMESTAMPS = new ConcurrentHashMap<>();

    private InternalWrites() {}

    /**
     * Marks {@code path} as internally written at the current wall-clock time.
     * Subsequent {@link #consumeInternalWrite} calls within the configured
     * window will return {@code true} and clear the mark.
     */
    public static void markInternalWrite(Path path) {
        if (path == null) return;
        TIMESTAMPS.put(path.toAbsolutePath().normalize().toString(), System.currentTimeMillis());
    }

    /**
     * Returns {@code true} if {@code path} was marked within the last
     * {@code windowMs} milliseconds. Consumes the mark on match — the watcher
     * fires once per write, so a matched mark shouldn't suppress the next
     * (real, external) change to the same file.
     */
    public static boolean consumeInternalWrite(Path path, long windowMs) {
        if (path == null) return false;
        String key = path.toAbsolutePath().normalize().toString();
        Long ts = TIMESTAMPS.get(key);
        if (ts != null && System.currentTimeMillis() - ts < windowMs) {
            TIMESTAMPS.remove(key);
            return true;
        }
        return false;
    }

    /** Clears every recorded internal-write mark. Test hook. */
    public static void clearInternalWrites() {
        TIMESTAMPS.clear();
    }
}
