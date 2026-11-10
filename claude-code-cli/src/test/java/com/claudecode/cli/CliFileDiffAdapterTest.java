package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.diff.FileChangeResult;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.UserMessage;
import com.claudecode.gateway.GatewayFileDiffPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The changed-files viewer's extraction: one turn's file summary and one file's
 * hunk comparison read out of a session's transcript.
 *
 * <p>The comparison is the tool record's own hunks, passed through rather than
 * reassembled into two whole-file sides: the viewer draws line numbers, hunk
 * headers and a side-by-side pairing, and each of those needs the per-hunk
 * offsets a flattened pair has already discarded. The assertions are therefore
 * about the offsets surviving intact, not about two documents reappearing.
 */
class CliFileDiffAdapterTest {

    private static final String SESSION = "session-a";
    private static final String PATH = "/src/App.java";

    /** A session whose transcript is the supplied messages, keyed by id. */
    private static CliFileDiffAdapter adapter(List<Message> messages) {
        return new CliFileDiffAdapter(
            sessionId -> SESSION.equals(sessionId) ? Optional.of(messages) : Optional.empty());
    }

    /** One turn: a human prompt, then a tool result carrying a file change. */
    private static List<Message> transcript(FileChangeResult... changes) {
        List<Message> messages = new ArrayList<>();
        messages.add(prompt("请改一下"));
        for (FileChangeResult change : changes) {
            messages.add(toolResult(change));
        }
        return messages;
    }

    private static UserMessage prompt(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text), false, false,
            null, MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static UserMessage toolResult(Object payload) {
        return new UserMessage("tr-" + System.nanoTime(), MessageContent.ofText(""), false, false,
            payload, MessageOrigin.USER, null, Instant.now(), null, null, null);
    }

    private static StructuredPatchHunk hunk(String... lines) {
        return new StructuredPatchHunk(1, lines.length, 1, lines.length, List.of(lines));
    }

    private static FileChangeResult edit(String path, String... lines) {
        return FileChangeResult.edited(path, List.of(hunk(lines)));
    }

    /**
     * The hunk reaches the viewer with every offset intact — the line-number
     * bases, the counts and the verbatim lines. A flattened pair would satisfy
     * a naive equality on the text while having lost all three.
     */
    @Test
    void passesTheHunksThroughIntact() {
        StructuredPatchHunk hunk = new StructuredPatchHunk(12, 4, 13, 5, List.of(
            " package app;", "-class Old {", "+class New {", " // tail", "+  void added() {}"));
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.edited(PATH, List.of(hunk))))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.kind()).isEqualTo(GatewayFileDiffPort.FileDiff.Kind.TEXT);
        assertThat(diff.hunks()).hasSize(1);
        StructuredPatchHunk sent = diff.hunks().getFirst();
        assertThat(sent.oldStart()).isEqualTo(12);
        assertThat(sent.oldLines()).isEqualTo(4);
        assertThat(sent.newStart()).isEqualTo(13);
        assertThat(sent.newLines()).isEqualTo(5);
        assertThat(sent.lines()).containsExactly(
            " package app;", "-class Old {", "+class New {", " // tail", "+  void added() {}");
    }

    /**
     * A created file has no prior version: the viewer names it from
     * {@code before=false} rather than inferring it from an absent side.
     */
    @Test
    void marksACreatedFileAsHavingNoBeforeSide() {
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.created(PATH, "package app;\nclass New {}")))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.kind()).isEqualTo(GatewayFileDiffPort.FileDiff.Kind.TEXT);
        assertThat(diff.before()).isFalse();
        assertThat(diff.after()).isTrue();
        // `coarse` is the viewer's "line comparison timed out" notice; a
        // synthesized create hunk is an exact whole-file addition, not a
        // timed-out comparison, so the notice must stay off.
        assertThat(diff.coarse()).isFalse();
    }

    /** An edit to an existing file has content on both sides. */
    @Test
    void marksAnEditAsHavingBothSides() {
        GatewayFileDiffPort.FileDiff diff = adapter(transcript(edit(PATH, "-a", "+b")))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.before()).isTrue();
        assertThat(diff.after()).isTrue();
        assertThat(diff.coarse()).isFalse();
    }

    /** A patch that leaves no new-side lines removed the file: there is no after side. */
    @Test
    void marksAFullyRemovedFileAsHavingNoAfterSide() {
        StructuredPatchHunk removal = new StructuredPatchHunk(1, 2, 0, 0, List.of("-a", "-b"));
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.edited(PATH, List.of(removal))))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.before()).isTrue();
        assertThat(diff.after()).isFalse();
    }

    /** A path no turn touched is absent, not an empty diff. */
    @Test
    void answersEmptyForAnUntouchedPath() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().diff(SESSION, "/src/Other.java")).isEmpty();
    }

    /** An unknown session answers empty rather than another session's file. */
    @Test
    void answersEmptyForAnUnknownSession() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().diff("no-such-session", PATH)).isEmpty();
    }

    /** A blank path is refused before any transcript is read. */
    @Test
    void answersEmptyForABlankPath() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().diff(SESSION, "  ")).isEmpty();
    }

    /**
     * The newest turn wins: a file written in two turns answers with its latest
     * state, which is what a reader clicking a delivered-file chip expects.
     */
    @Test
    void answersWithTheNewestTurnThatTouchedThePath() {
        List<Message> messages = new ArrayList<>(transcript(edit(PATH, "-first", "+second")));
        messages.add(prompt("再改一次"));
        messages.add(toolResult(edit(PATH, "-second", "+third")));

        GatewayFileDiffPort.FileDiff diff = adapter(messages)
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.hunks()).hasSize(1);
        assertThat(diff.hunks().getFirst().lines()).containsExactly("-second", "+third");
    }

    /** A content line carrying a NUL is not text; it reports as binary. */
    @Test
    void reportsNonTextContentAsBinary() {
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.created("/bin/blob.dat", "before\0after")))
            .port().diff(SESSION, "/bin/blob.dat").orElseThrow();

        assertThat(diff.kind()).isEqualTo(GatewayFileDiffPort.FileDiff.Kind.BINARY);
    }

    // ── summary ──────────────────────────────────────────────────────────────

    /**
     * A turn's summary lists its files with their own counts, and the totals
     * the card heads itself with.
     */
    @Test
    void readsATurnSummary() {
        List<Message> messages = new ArrayList<>(transcript(
            edit(PATH, " a", "-old", "+new"),
            FileChangeResult.created("/src/New.java", "package app;\nclass New {}")));

        GatewayFileDiffPort.ChangesSummary summary = adapter(messages)
            .port().summary(SESSION, 1).orElseThrow();

        assertThat(summary.turn()).isEqualTo(1);
        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.files()).extracting(GatewayFileDiffPort.ChangedFile::path)
            .containsExactlyInAnyOrder(PATH, "/src/New.java");
        // The header total is the sum of the rows, not an independent count.
        assertThat(summary.added())
            .isEqualTo(summary.files().stream().mapToInt(GatewayFileDiffPort.ChangedFile::added).sum());
        assertThat(summary.deleted())
            .isEqualTo(summary.files().stream().mapToInt(GatewayFileDiffPort.ChangedFile::deleted).sum());
    }

    /**
     * Turn numbers are the transcript's own 1-based numbering — the same number
     * the webui stamps on each row — so an unresolved turn is empty rather than
     * a neighbouring turn's files.
     */
    @Test
    void answersEmptyForATurnThatChangedNothing() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().summary(SESSION, 2)).isEmpty();
    }

    /** Turn numbers below one address no turn at all. */
    @Test
    void answersEmptyForATurnBelowOne() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().summary(SESSION, 0)).isEmpty();
    }

    /** An unknown session answers empty rather than another session's files. */
    @Test
    void answersEmptyForAnUnknownSessionSummary() {
        CliFileDiffAdapter adapter = adapter(transcript(edit(PATH, "-a", "+b")));

        assertThat(adapter.port().summary("no-such-session", 1)).isEmpty();
    }

    /** The port's own empty instance answers nothing, so the routes can 404. */
    @Test
    void theEmptyPortAnswersNothing() {
        assertThat(GatewayFileDiffPort.none().diff(SESSION, PATH)).isEmpty();
        assertThat(GatewayFileDiffPort.none().summary(SESSION, 1)).isEmpty();
    }
}