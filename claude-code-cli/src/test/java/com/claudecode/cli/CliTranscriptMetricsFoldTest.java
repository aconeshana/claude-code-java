package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The replay behind a remote session's stats pills: a transcript written by
 * another process must fold to the same numbers the TUI status line shows,
 * and a transcript the fold cannot vouch for must report <em>absent</em>
 * rather than a smaller total.
 */
class CliTranscriptMetricsFoldTest {

    private final CliTranscriptMetricsFold fold = new CliTranscriptMetricsFold();

    @Test
    void replaysThePersistedRowsIntoASessionTotal(@TempDir Path project) throws IOException {
        Path transcript = project.resolve("s-1.jsonl");
        Files.writeString(transcript, rows("s-1"));

        Optional<SessionMetricsSnapshot> metrics = fold.of("s-1", transcript);

        assertThat(metrics).isPresent();
        assertThat(metrics.get().complete()).isTrue();
        assertThat(metrics.get().turns()).isEqualTo(1);
        assertThat(metrics.get().uncachedInputTokens()).isEqualTo(120);
        assertThat(metrics.get().outputTokens()).isEqualTo(40);
    }

    /**
     * Rows of a different session cannot be replayed as this one's total: the
     * tracker rejects the sequence and the fold reports absent, never a
     * partial sum.
     */
    @Test
    void reportsAbsentWhenTheRowsBelongToAnotherSession(@TempDir Path project) throws IOException {
        Path transcript = project.resolve("s-1.jsonl");
        Files.writeString(transcript, rows("other-session"));

        assertThat(fold.of("s-1", transcript)).isEmpty();
    }

    @Test
    void reportsAbsentWhenTheTranscriptIsMissing(@TempDir Path project) {
        assertThat(fold.of("s-1", project.resolve("absent.jsonl"))).isEmpty();
    }

    @Test
    void reportsAbsentWithoutASessionId(@TempDir Path project) throws IOException {
        Path transcript = project.resolve("s-1.jsonl");
        Files.writeString(transcript, rows("s-1"));

        assertThat(fold.of(null, transcript)).isEmpty();
    }

    /** One measured turn, in the seq order the tracker's strict replay requires. */
    private static String rows(String sessionId) {
        SessionMetricsEvent.Kind[] kinds = {
            SessionMetricsEvent.Kind.SESSION_START,
            SessionMetricsEvent.Kind.TURN_START,
            SessionMetricsEvent.Kind.STEP_START,
            SessionMetricsEvent.Kind.ASSISTANT_USAGE,
            SessionMetricsEvent.Kind.STEP_END,
            SessionMetricsEvent.Kind.TURN_END};
        StringBuilder body = new StringBuilder();
        for (int seq = 0; seq < kinds.length; seq++) {
            boolean inTurn = seq >= 1 && seq <= 4;
            SessionMetricsEvent event = new SessionMetricsEvent(
                SessionMetricsEvent.SCHEMA_VERSION, seq, 1_000L + seq, sessionId, kinds[seq],
                inTurn ? "turn-1" : null, inTurn ? 1 : 0, seq >= 2 ? 1 : 0, null,
                seq == 3 ? 120 : 0, seq == 3 ? 40 : 0, 0, 0, false);
            // The persisted shape is the transcript envelope, not the raw record.
            ObjectNode row = JsonUtils.getMapper().valueToTree(event).deepCopy();
            row.put("type", SessionMetricsEvent.TRANSCRIPT_TYPE);
            row.put("event", kinds[seq].wireName());
            body.append(row).append('\n');
        }
        return body.toString();
    }
}
