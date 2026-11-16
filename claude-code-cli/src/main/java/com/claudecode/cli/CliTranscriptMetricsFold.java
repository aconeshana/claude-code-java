package com.claudecode.cli;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.session.SessionStorage;
import com.claudecode.runtime.metrics.SessionMetricsTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The durable HUD fold of a session this process did not open, replayed from
 * its on-disk transcript.
 *
 * <p>A standalone {@code claude web} gateway runs no engine, so a session a
 * TUI process hosts has no in-memory fold here. Its metrics are nonetheless
 * durable rows in the transcript the TUI wrote, and the fold over them is
 * pure — {@link SessionMetricsTracker#restore} requires nothing beyond the
 * persisted events and the transcript's own turn ids. Replaying the same two
 * inputs the TUI's {@code /resume} and the headless session opener consume
 * therefore yields the same numbers the TUI status line shows, with no round
 * trip to the owning process.
 *
 * <p>A partial fold is reported as absent, never as a smaller total
 * ({@code docs/hud-metrics-specification.md} §6): the tracker marks itself
 * {@code INCOMPLETE} when the event sequence has gaps or a transcript turn
 * was never measured, and {@link SessionMetricsSnapshot#complete()} then
 * fails the filter.
 */
final class CliTranscriptMetricsFold {

    /**
     * The sink of a read-only replay. The tracker only consults it when a
     * fold advances, and a restore never does, but a no-op keeps a future
     * caller from writing into another process's session.
     */
    private static final TranscriptSink NEVER_RECORDS = (_, _) -> { };

    private final SessionStorage storage = new SessionStorage();

    /**
     * The whole-session metrics of the transcript at {@code sessionFile}, or
     * empty when the file is absent, unreadable, or its fold is incomplete.
     */
    Optional<SessionMetricsSnapshot> of(String sessionId, Path sessionFile) {
        if (sessionId == null || sessionFile == null || !Files.isRegularFile(sessionFile)) {
            return Optional.empty();
        }
        try {
            // The replay target's sink is never installed: nothing is added to
            // this session here, the fold is only ever read.
            SessionMetricsTracker tracker = new SessionMetricsTracker(
                sessionId, () -> NEVER_RECORDS);
            tracker.restore(sessionId,
                storage.readSessionMetrics(sessionFile),
                storage.readMetricTurnIds(sessionFile));
            SessionMetricsSnapshot snapshot = tracker.snapshot();
            return snapshot.complete() ? Optional.of(snapshot) : Optional.empty();
        } catch (RuntimeException _) {
            return Optional.empty();
        }
    }
}
