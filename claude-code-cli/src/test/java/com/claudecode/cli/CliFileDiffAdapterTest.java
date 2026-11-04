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
 * The changed-files viewer's extraction: one file's before/after sides read out
 * of a session's transcript.
 *
 * <p>The sides come from the recorded hunks rather than from disk, so the
 * assertions are about reassembling a hunk's removed/added/context lines back
 * into the two documents a reader compares — including the created-file case,
 * whose old side must stay null so the viewer draws no removed lines.
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

    /** Context, removed and added lines reassemble into the two compared sides. */
    @Test
    void readsBothSidesFromOneHunk() {
        GatewayFileDiffPort.FileDiff diff = adapter(transcript(
            edit(PATH, " package app;", "-class Old {", "+class New {")))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.kind()).isEqualTo(GatewayFileDiffPort.FileDiff.Kind.TEXT);
        assertThat(diff.oldText()).isEqualTo("package app;\nclass Old {\n");
        assertThat(diff.newText()).isEqualTo("package app;\nclass New {\n");
    }

    /**
     * A created file has no old side: the viewer must draw no removed lines,
     * which is what a null old side means to it.
     */
    @Test
    void leavesTheOldSideNullForACreatedFile() {
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.created(PATH, "package app;\nclass New {}")))
            .port().diff(SESSION, PATH).orElseThrow();

        assertThat(diff.oldText()).isNull();
        assertThat(diff.newText()).contains("package app;");
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

        assertThat(diff.oldText()).isEqualTo("second\n");
        assertThat(diff.newText()).isEqualTo("third\n");
    }

    /** A content line carrying a NUL is not text; it reports as binary. */
    @Test
    void reportsNonTextContentAsBinary() {
        GatewayFileDiffPort.FileDiff diff = adapter(
            transcript(FileChangeResult.created("/bin/blob.dat", "before\0after")))
            .port().diff(SESSION, "/bin/blob.dat").orElseThrow();

        assertThat(diff.kind()).isEqualTo(GatewayFileDiffPort.FileDiff.Kind.BINARY);
    }

    /** The port's own empty instance answers nothing, so the route can 404. */
    @Test
    void theEmptyPortAnswersNothing() {
        assertThat(GatewayFileDiffPort.none().diff(SESSION, PATH)).isEmpty();
    }
}