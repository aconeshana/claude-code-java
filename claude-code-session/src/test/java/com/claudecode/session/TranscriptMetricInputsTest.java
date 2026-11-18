package com.claudecode.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.claudecode.core.metrics.SessionMetricsEvent;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The incremental metric inputs must always equal the two whole-file reads
 * they replace — through appends, an unfinished last line, a sidecar coming
 * and going, and a file that was rewritten rather than appended to.
 */
class TranscriptMetricInputsTest {

    @TempDir Path dir;

    private final SessionStorage storage = new SessionStorage();
    private final TranscriptMetricInputs inputs = new TranscriptMetricInputs();

    private static String metric(long seq, String turnId) {
        return "{\"type\":\"" + SessionMetricsEvent.TRANSCRIPT_TYPE
            + "\",\"schemaVersion\":1,\"seq\":" + seq + ",\"time\":" + (1000 + seq)
            + ",\"sessionId\":\"s-1\",\"event\":\"turn/start\""
            + (turnId == null ? "" : ",\"turnId\":\"" + turnId + "\"")
            + ",\"turn\":1,\"step\":0,\"uncachedInputTokens\":" + seq + ",\"outputTokens\":2}";
    }

    private static String prompt(String uuid, String parent) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"promptSource\":\"typed\""
            + (parent == null ? "" : ",\"parentUuid\":\"" + parent + "\"")
            + ",\"message\":{\"role\":\"user\",\"content\":\"hi\"}}";
    }

    private static String toolResult(String uuid) {
        return "{\"type\":\"user\",\"uuid\":\"" + uuid + "\",\"message\":{\"role\":\"user\","
            + "\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t\",\"content\":\"ok\"}]}}";
    }

    private static String lines(String... rows) {
        return String.join("\n", rows) + "\n";
    }

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** What the incremental read must equal: the whole-file reads, now. */
    private void assertMatchesWholeFileReads(Path file) {
        TranscriptMetricInputs.MetricInputs read = inputs.read(file);
        assertThat(read.events()).isEqualTo(storage.readSessionMetrics(file));
        assertThat(read.turnIds())
            .containsExactlyInAnyOrderElementsOf(storage.readMetricTurnIds(file));
    }

    @Test
    void firstReadMatchesTheWholeFileReads() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1"), metric(1, "p1")));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).containsExactly("p1");
        assertThat(inputs.read(file).events()).hasSize(2);
    }

    @Test
    void anUnchangedFileAnswersWithTheSameInstance() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1")));

        assertThat(inputs.read(file)).isSameAs(inputs.read(file));
    }

    @Test
    void appendedLinesExtendTheInputs() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1")));
        TranscriptMetricInputs.MetricInputs before = inputs.read(file);

        append(file, lines(toolResult("r1"), prompt("p2", "r1"), prompt("p3", null), metric(1, "p3")));

        assertThat(inputs.read(file)).isNotSameAs(before);
        assertMatchesWholeFileReads(file);
        // p2 is chained onto a tool result: a mid-turn injection, not a turn.
        assertThat(inputs.read(file).turnIds()).containsExactlyInAnyOrder("p1", "p3");
    }

    /** The parent rule reaches back into rows read by an earlier pass. */
    @Test
    void aLaterPromptSeesAParentReadInAnEarlierPass() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(toolResult("r1")));
        inputs.read(file);

        append(file, lines(prompt("p2", "r1")));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).isEmpty();
    }

    /** A later row with the same uuid replaces the earlier one, as the uuid map does. */
    @Test
    void aRepeatedUuidKeepsItsLatestRow() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null)));
        assertThat(inputs.read(file).turnIds()).containsExactly("p1");

        append(file, lines(toolResult("p1")));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).isEmpty();
    }

    @Test
    void anUnfinishedLastLineCountsWithoutBeingSettled() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1")) + metric(1, "p1"));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(2);

        // The line finishes and more follow; the earlier tail is not counted twice.
        append(file, "\n" + lines(prompt("p2", null)) + "{\"type\":\"user\",\"uu");

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(2);
        assertThat(inputs.read(file).turnIds()).containsExactlyInAnyOrder("p1", "p2");
    }

    @Test
    void anUnfinishedPromptRowIsLaidOverTheIndex() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(toolResult("r1")) + prompt("p2", null));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).containsExactly("p2");
    }

    @Test
    void malformedLinesAreSkippedLikeTheWholeFileReads() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), "{not json", "", metric(0, "p1")));

        assertMatchesWholeFileReads(file);
    }

    @Test
    void aByteOrderMarkOpeningTheFileIsIgnored() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, "\uFEFF" + lines(prompt("p1", null), metric(0, "p1")));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).containsExactly("p1");
    }

    @Test
    void theSidecarSuppliesTheEventsWhileItExists() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1")));
        assertMatchesWholeFileReads(file);

        Path sidecar = SessionMetricsFiles.sidecar(file);
        Files.writeString(sidecar, lines(metric(0, "p1"), metric(1, "p1"), metric(2, "p1")));
        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(3);

        append(sidecar, lines(metric(3, "p1")));
        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(4);

        Files.delete(sidecar);
        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(1);
    }

    @Test
    void aRewrittenFileIsReadAgainFromItsStart() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1"), metric(1, "p1")));
        inputs.read(file);

        // Longer, with different leading bytes, rewritten in place.
        Files.writeString(file, lines(prompt("qq1", null), metric(0, "qq1"), metric(1, "qq1")));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).turnIds()).containsExactly("qq1");
    }

    @Test
    void aReplacedFileIsReadAgainFromItsStart() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1")));
        inputs.read(file);

        Path replacement = dir.resolve("next.tmp");
        Files.writeString(replacement, lines(prompt("p1", null), metric(0, "p1"), prompt("p2", null)));
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).hasSize(1);
    }

    @Test
    void aTruncatedFileIsReadAgainFromItsStart() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null), metric(0, "p1"), prompt("p2", null)));
        inputs.read(file);

        Files.writeString(file, lines(prompt("p1", null)));

        assertMatchesWholeFileReads(file);
        assertThat(inputs.read(file).events()).isEmpty();
    }

    @Test
    void aMissingFileHasNoInputs() {
        TranscriptMetricInputs.MetricInputs read = inputs.read(dir.resolve("absent.jsonl"));

        assertThat(read.events()).isEmpty();
        assertThat(read.turnIds()).isEmpty();
    }

    @Test
    void malformedUtf8FailsRatherThanAnsweringAPartialRead() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        Files.writeString(file, lines(prompt("p1", null)));
        inputs.read(file);
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'}, StandardOpenOption.APPEND);

        assertThatThrownBy(() -> inputs.read(file)).isInstanceOf(UncheckedIOException.class);
    }

    /** Many growth steps, each one matching a whole-file read of that moment. */
    @Test
    void everyAppendStepMatchesTheWholeFileReads() throws Exception {
        Path file = dir.resolve("s-1.jsonl");
        List<String> previous = new ArrayList<>();
        for (int round = 0; round < 30; round++) {
            String uuid = "p" + round;
            String parent = previous.isEmpty() ? null : previous.getLast();
            String row = round % 3 == 0 ? toolResult(uuid) : prompt(uuid, parent);
            append(file, lines(row, metric(round, uuid)));
            previous.add(uuid);
            assertMatchesWholeFileReads(file);
        }
    }
}
