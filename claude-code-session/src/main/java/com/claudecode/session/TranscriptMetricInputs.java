package com.claudecode.session;

import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * {@link SessionStorage#readSessionMetrics} and
 * {@link SessionStorage#readMetricTurnIds} for a reader that replays the same
 * session's metrics over and over while another process keeps writing it: the
 * webui gateway refreshing a remote session's stats pills.
 *
 * <p>Those two reads parse the whole transcript into trees, twice, and a
 * session that is being worked on changes between every refresh, so a cache
 * keyed on an unchanged file never hits. Here each file is read once and
 * afterwards only by the lines appended to it ({@link AppendCursor}): the
 * metric rows already read are kept as events, and the rows the turn rule
 * reads as the few facts it needs ({@link MetricTurnIndex}). A file that was
 * replaced, truncated or rewritten is read again from its start.
 *
 * <p>The answer is the one the two whole-file reads give, with one
 * difference: the whole-file reader only reads the last 100 MB of a larger
 * file, which drops the head of the metric sequence and the turns it
 * measured, whereas this reads every line.
 *
 * <p>Concurrent reads of one session share a single read. Each session keeps
 * its events and a small index; a reader left up for weeks forgets them all
 * once it has seen more sessions than it keeps.
 */
public final class TranscriptMetricInputs {

    /** Each kept session is its events and row facts, not its transcript. */
    private static final int MAX_KEPT_SESSIONS = 64;

    /**
     * The two inputs a metrics replay consumes.
     *
     * @param events the metric events, from the sidecar when it exists
     * @param turnIds the transcript turns that each owe one measured turn
     */
    public record MetricInputs(List<SessionMetricsEvent> events, List<String> turnIds) {
        public MetricInputs {
            events = List.copyOf(events);
            turnIds = List.copyOf(turnIds);
        }

        static final MetricInputs NONE = new MetricInputs(List.of(), List.of());
    }

    private final ConcurrentMap<Path, Slot> slots = new ConcurrentHashMap<>();

    /**
     * The metric events and turn ids of the transcript at {@code sessionFile},
     * as the whole-file reads would return them now.
     *
     * <p>While neither the transcript nor its metrics sidecar changed, the
     * same instance is returned, so a caller can keep what it computed from
     * it by identity.
     *
     * @return empty inputs when the transcript does not exist
     * @throws UncheckedIOException when a file cannot be read or decoded
     */
    public MetricInputs read(Path sessionFile) {
        Path key = sessionFile.toAbsolutePath().normalize();
        if (slots.size() >= MAX_KEPT_SESSIONS && !slots.containsKey(key)) slots.clear();
        Slot slot = slots.computeIfAbsent(key, _ -> new Slot());
        slot.lock.lock();
        try {
            return slot.refresh(key);
        } catch (IOException e) {
            slot.forget();
            throw new UncheckedIOException("Failed to read session metrics: " + key, e);
        } catch (RuntimeException e) {
            slot.forget();
            throw e;
        } finally {
            slot.lock.unlock();
        }
    }

    /** One session's read state, guarded so concurrent reads share one. */
    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private Transcript transcript;
        private Sidecar sidecar;
        private MetricInputs inputs;

        MetricInputs refresh(Path sessionFile) throws IOException {
            BasicFileAttributes transcriptAttributes = attributesOf(sessionFile);
            if (transcriptAttributes == null) {
                forget();
                return MetricInputs.NONE;
            }
            Path sidecarFile = SessionMetricsFiles.sidecar(sessionFile);
            BasicFileAttributes sidecarAttributes = attributesOf(sidecarFile);
            if (inputs != null
                    && transcript.cursor.describes(transcriptAttributes)
                    && sidecarUnchanged(sidecarAttributes)) {
                return inputs;
            }
            transcript = Transcript.advance(transcript, sessionFile, transcriptAttributes);
            sidecar = sidecarAttributes == null
                ? null
                : Sidecar.advance(sidecar, sidecarFile, sidecarAttributes);
            inputs = new MetricInputs(
                sidecar != null ? sidecar.events() : transcript.events(),
                transcript.turnIds());
            return inputs;
        }

        private boolean sidecarUnchanged(BasicFileAttributes attributes) {
            if (sidecar == null) return attributes == null;
            return attributes != null && sidecar.cursor.describes(attributes);
        }

        void forget() {
            transcript = null;
            sidecar = null;
            inputs = null;
        }
    }

    /**
     * The transcript's metric rows and turn-rule facts read so far. The index
     * only ever holds settled lines; an unfinished last line is laid over it.
     */
    private record Transcript(AppendCursor cursor, List<SessionMetricsEvent> settledEvents,
            MetricTurnIndex index, List<JsonNode> unfinished) {

        static Transcript advance(Transcript previous, Path file,
                BasicFileAttributes attributes) throws IOException {
            AppendCursor.Appended appended = read(
                previous == null ? null : previous.cursor, file, attributes);
            boolean fresh = previous == null || appended.restarted();
            MetricTurnIndex index = fresh ? new MetricTurnIndex() : previous.index;
            List<JsonNode> settled = rows(appended.settledLines(), appended.firstSettledLine());
            settled.forEach(index::accept);
            List<SessionMetricsEvent> events = concat(
                fresh ? List.of() : previous.settledEvents, eventsOf(settled));
            return new Transcript(appended.cursor(), events, index,
                rows(appended.unfinishedLines(), appended.firstUnfinishedLine()));
        }

        List<SessionMetricsEvent> events() {
            return concat(settledEvents, eventsOf(unfinished));
        }

        List<String> turnIds() {
            return unfinished.isEmpty()
                ? index.turnIds()
                : index.turnIds(MetricTurnIndex.rowsOf(unfinished));
        }
    }

    /** The sidecar's metric events read so far. */
    private record Sidecar(AppendCursor cursor, List<SessionMetricsEvent> settledEvents,
            List<SessionMetricsEvent> unfinishedEvents) {

        static Sidecar advance(Sidecar previous, Path file, BasicFileAttributes attributes)
                throws IOException {
            AppendCursor.Appended appended = read(
                previous == null ? null : previous.cursor, file, attributes);
            boolean fresh = previous == null || appended.restarted();
            List<SessionMetricsEvent> events = concat(
                fresh ? List.of() : previous.settledEvents,
                eventsOf(rows(appended.settledLines(), appended.firstSettledLine())));
            return new Sidecar(appended.cursor(), events,
                eventsOf(rows(appended.unfinishedLines(), appended.firstUnfinishedLine())));
        }

        List<SessionMetricsEvent> events() {
            return concat(settledEvents, unfinishedEvents);
        }
    }

    private static AppendCursor.Appended read(AppendCursor previous, Path file,
            BasicFileAttributes attributes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return AppendCursor.read(previous, channel, attributes);
        }
    }

    private static BasicFileAttributes attributesOf(Path file) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return attributes.isRegularFile() ? attributes : null;
        } catch (NoSuchFileException _) {
            return null;
        }
    }

    /**
     * The rows of some lines, parsed as the whole-file reader parses them:
     * blank and malformed lines are skipped, and a byte-order mark only ever
     * opens a file's first line.
     */
    private static List<JsonNode> rows(List<String> lines, long firstLineNumber) {
        List<JsonNode> rows = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String raw = firstLineNumber + i == 1 ? JsonUtils.stripBom(lines.get(i)) : lines.get(i);
            String line = raw.trim();
            if (line.isEmpty()) continue;
            try {
                rows.add(JsonUtils.parseTree(line));
            } catch (UncheckedIOException _) {
                // A malformed row carries nothing a whole-file read would keep.
            }
        }
        return rows;
    }

    private static List<SessionMetricsEvent> eventsOf(List<JsonNode> rows) {
        List<SessionMetricsEvent> events = new ArrayList<>();
        for (JsonNode row : rows) {
            SessionMetricsEvent event = SessionStorage.metricRow(row);
            if (event != null) events.add(event);
        }
        return events;
    }

    private static <T> List<T> concat(List<T> head, List<T> tail) {
        if (tail.isEmpty()) return List.copyOf(head);
        return Stream.concat(head.stream(), tail.stream()).toList();
    }
}
