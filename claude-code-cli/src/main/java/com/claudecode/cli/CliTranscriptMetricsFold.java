package com.claudecode.cli;

import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.session.TranscriptMetricInputs;
import com.claudecode.runtime.metrics.SessionMetricsTracker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <p>The webui asks again with every context refresh, and a session that is
 * being worked on has grown since the last one. Its inputs are therefore read
 * incrementally ({@link TranscriptMetricInputs}), and the fold over them is
 * kept and replayed only when they changed; concurrent requests for one file
 * share a single read.
 */
final class CliTranscriptMetricsFold {

    /**
     * The sink of a read-only replay. The tracker only consults it when a
     * fold advances, and a restore never does, but a no-op keeps a future
     * caller from writing into another process's session.
     */
    private static final TranscriptSink NEVER_RECORDS = (_, _) -> { };

    /** Folds are a few numbers each; this only bounds a gateway left up for weeks. */
    private static final int MAX_KEPT_FOLDS = 64;

    private final TranscriptMetricInputs inputs = new TranscriptMetricInputs();
    private final ConcurrentMap<Path, Slot> folds = new ConcurrentHashMap<>();

    /**
     * The whole-session metrics of the transcript at {@code sessionFile}, or
     * empty when the file is absent, unreadable, or its fold is incomplete.
     */
    Optional<SessionMetricsSnapshot> of(String sessionId, Path sessionFile) {
        if (sessionId == null || sessionFile == null || !Files.isRegularFile(sessionFile)) {
            return Optional.empty();
        }
        Path key = sessionFile.toAbsolutePath().normalize();
        if (folds.size() >= MAX_KEPT_FOLDS && !folds.containsKey(key)) folds.clear();
        Slot slot = folds.computeIfAbsent(key, _ -> new Slot());
        slot.lock.lock();
        try {
            TranscriptMetricInputs.MetricInputs current = inputs.read(key);
            Memo memo = slot.memo;
            if (memo != null && memo.answers(sessionId, current)) return memo.metrics();
            Optional<SessionMetricsSnapshot> metrics = replay(sessionId, current);
            slot.memo = new Memo(sessionId, current, metrics);
            return metrics;
        } catch (RuntimeException _) {
            slot.memo = null;
            return Optional.empty();
        } finally {
            slot.lock.unlock();
        }
    }

    private static Optional<SessionMetricsSnapshot> replay(String sessionId,
            TranscriptMetricInputs.MetricInputs inputs) {
        // The replay target's sink is never installed: nothing is added to
        // this session here, the fold is only ever read.
        SessionMetricsTracker tracker = new SessionMetricsTracker(sessionId, () -> NEVER_RECORDS);
        tracker.restore(sessionId, inputs.events(), inputs.turnIds());
        SessionMetricsSnapshot snapshot = tracker.snapshot();
        return snapshot.complete() ? Optional.of(snapshot) : Optional.empty();
    }

    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private Memo memo;
    }

    /** A fold, valid for exactly the inputs instance it was replayed from. */
    private record Memo(String sessionId, TranscriptMetricInputs.MetricInputs inputs,
            Optional<SessionMetricsSnapshot> metrics) {
        boolean answers(String requested, TranscriptMetricInputs.MetricInputs current) {
            return sessionId.equals(requested) && inputs == current;
        }
    }
}
