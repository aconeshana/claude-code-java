package com.claudecode.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Persisted wire shape of {@link FileProjectIndexStore} — primitives/strings only
 * (epoch millis instead of {@code Instant}) to keep the native-image reflection
 * surface minimal and the format stable. This is a pure cache: the session jsonl
 * files are the source of truth and any unreadable/foreign snapshot rebuilds to
 * {@link #empty()}. No 197 counterpart (the original keeps no project index).
 *
 * <p>Entries are cached <b>per transcript directory</b>, not per project: a
 * session's effective project is its content {@code cwd} ({@code relocatedCwd}
 * wins), which may differ from the directory it is stored in — so per-dir
 * fingerprints revalidate the cache, and cwd-grouping into {@link ProjectInfo}
 * happens only after all dirs are merged.
 *
 * @param version           schema version; only {@link #CURRENT_VERSION} is accepted
 * @param dirs              cached per-directory session buckets with revalidation fingerprints
 * @param pinnedProjects    user pref — pinned project paths, carried across rebuilds
 * @param collapsedProjects user pref — project path → collapsed, carried across rebuilds
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectIndexSnapshot(
    int version,
    List<CachedDir> dirs,
    List<String> pinnedProjects,
    Map<String, Boolean> collapsedProjects
) {
    public static final int CURRENT_VERSION = 1;

    /**
     * @param dirName        sanitized directory under {@code projects/} (locator)
     * @param fileCount      revalidation fingerprint — .jsonl count in the dir
     * @param maxFileMtimeMs revalidation fingerprint — newest .jsonl mtime; the dir
     *                       mtime alone cannot see appends to existing transcripts
     * @param sessions       cached session metadata, lastModified descending
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CachedDir(
        String dirName,
        int fileCount,
        long maxFileMtimeMs,
        List<CachedSession> sessions
    ) {}

    /** Lean persisted projection of {@link SessionInfo} (epoch millis, no Instant). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CachedSession(
        String id,
        String cwd,
        long lastModifiedMs,
        long createdAtMs,
        int messageCount,
        String summary,
        String gitBranch,
        String tag,
        String customTitle,
        String firstPrompt,
        long fileSize
    ) {}

    public static ProjectIndexSnapshot empty() {
        return new ProjectIndexSnapshot(CURRENT_VERSION, List.of(), List.of(), Map.of());
    }
}
