package com.claudecode.commands.session;

import com.claudecode.core.message.Message;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session persistence and catalog capabilities consumed by commands.
 */
public interface SessionCommandPort {
    record LocatedSession(String id, Path sessionFile, String cwd, String customTitle) {}
    record ForkResult(Status status, int messageCount, List<Message> messages) {
        public enum Status { SUCCESS, NO_CONVERSATION, NO_MESSAGES }
        public ForkResult { messages = List.copyOf(messages); }
    }
    record ModelUsage(long inputTokens, long outputTokens) {}
    record SessionDuration(long duration) {}
    record Streaks(int longestStreak, int currentStreak) {}
    record StatsSnapshot(long totalSessions, Map<String, ModelUsage> modelUsage,
                         SessionDuration longestSession, int activeDays, int totalDays,
                         Streaks streaks, LocalDate peakActivityDay) {
        public StatsSnapshot { modelUsage = Map.copyOf(modelUsage); }
    }

    List<LocatedSession> listSessions();
    Optional<LocatedSession> findExactSessionId(String id);
    List<LocatedSession> searchExactCustomTitle(String title);
    List<Message> readMessages(Path transcript);
    String createSession();
    Path transcriptPath(String sessionId);
    boolean hasTranscript(String sessionId);
    ForkResult fork(String sourceSessionId, String forkSessionId);
    /** Appends extra conversation messages after a raw transcript fork (used by /btw fork). */
    default void appendMessages(String sessionId, List<Message> messages) {
        throw new IllegalStateException("Session message append is not wired");
    }
    void saveCustomTitle(String sessionId, String title);
    void saveAgentName(String sessionId, String name);
    void saveAgentColor(String sessionId, String color);
    void saveTag(String sessionId, String tag);
    String readTag(String sessionId);
    String readCustomTitle(String sessionId);
    Path toolResultsDirectory(String sessionId);
    /** Best-effort .session-aliases update after /add-dir succeeds. */
    default void recordSessionAlias(Path targetDirectory, String activeSessionId) {}
    StatsSnapshot stats();

    static SessionCommandPort none() {
        return new SessionCommandPort() {
            private IllegalStateException unavailable() {
                return new IllegalStateException("Session command port is not wired");
            }
            @Override public List<LocatedSession> listSessions() { return List.of(); }
            @Override public Optional<LocatedSession> findExactSessionId(String id) { return Optional.empty(); }
            @Override public List<LocatedSession> searchExactCustomTitle(String title) { return List.of(); }
            @Override public List<Message> readMessages(Path transcript) { throw unavailable(); }
            @Override public String createSession() { throw unavailable(); }
            @Override public Path transcriptPath(String sessionId) { throw unavailable(); }
            @Override public boolean hasTranscript(String sessionId) { return false; }
            @Override public ForkResult fork(String sourceSessionId, String forkSessionId) { throw unavailable(); }
            @Override public void saveCustomTitle(String sessionId, String title) { throw unavailable(); }
            @Override public void saveAgentName(String sessionId, String name) { throw unavailable(); }
            @Override public void saveAgentColor(String sessionId, String color) { throw unavailable(); }
            @Override public void saveTag(String sessionId, String tag) { throw unavailable(); }
            @Override public String readTag(String sessionId) { return null; }
            @Override public String readCustomTitle(String sessionId) { return null; }
            @Override public Path toolResultsDirectory(String sessionId) { throw unavailable(); }
            @Override public StatsSnapshot stats() {
                return new StatsSnapshot(0, Map.of(), null, 0, 0, new Streaks(0, 0), null);
            }
        };
    }
}
