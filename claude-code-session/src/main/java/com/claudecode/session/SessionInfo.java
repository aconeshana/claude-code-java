package com.claudecode.session;

import java.time.Instant;

/**
 * Metadata about a stored session, returned by {@link SessionManager#listSessions}.
 */
public record SessionInfo(
    String id,
    long lastModified,
    Instant createdAt,
    int messageCount,
    String summary,
    String gitBranch,
    String cwd,
    String tag,
    long fileSize,
    String customTitle,
    String firstPrompt
) {
    /** Backward-compatible constructor for the pre-lite-catalog projection. */
    public SessionInfo(String id, long lastModified, Instant createdAt, int messageCount,
                       String summary, String gitBranch, String cwd, String tag) {
        this(id, lastModified, createdAt, messageCount, summary, gitBranch, cwd, tag,
            -1L, null, null);
    }

    /** Backward-compatible constructor without tag. */
    public SessionInfo(String id, long lastModified, Instant createdAt, int messageCount,
                       String summary, String gitBranch, String cwd) {
        this(id, lastModified, createdAt, messageCount, summary, gitBranch, cwd, null,
            -1L, null, null);
    }

    /** Backward-compatible constructor for callers that don't have the new fields. */
    public SessionInfo(String id, Instant createdAt, int messageCount, String lastModel) {
        this(id,
             createdAt != null ? createdAt.toEpochMilli() : 0L,
             createdAt,
             messageCount,
             null,
             null,
             null,
             null,
             -1L,
             null,
             null);
    }
}
