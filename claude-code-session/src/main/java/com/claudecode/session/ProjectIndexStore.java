package com.claudecode.session;

/**
 * Persistence port for the project-panel index cache — a Java-side extension
 * (no 197 counterpart; Wake persists the same role into SQLite, we start with a
 * single JSON file behind this seam so a SQLite store can replace it later
 * without touching the panel).
 *
 * <p>Implementations are tolerant caches: the session jsonl files stay the
 * source of truth, so reads never throw and writes only log.
 */
public interface ProjectIndexStore {

    /**
     * Reads the cached snapshot. Missing, corrupt, or foreign-version caches
     * return {@link ProjectIndexSnapshot#empty()} — never throws.
     */
    ProjectIndexSnapshot load();

    /**
     * Persists the snapshot atomically (temp file + rename). I/O failures are
     * logged and swallowed: a lost cache only costs one rescan.
     */
    void save(ProjectIndexSnapshot snapshot);

    /**
     * Drops one project's cached entry (by its sanitized directory name under
     * {@code projects/}) so the next scan rebuilds it. No-op when the entry is
     * absent — in particular it must not create the cache file.
     */
    void invalidate(String dirName);
}
