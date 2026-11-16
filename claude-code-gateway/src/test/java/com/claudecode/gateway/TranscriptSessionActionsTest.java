package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.claudecode.runtime.webworker.WebWorkerDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The session row menu's mutating actions for a session this process does not
 * host, answered from the transcript on disk.
 *
 * <p>This is the standalone {@code claude web} gateway's case: it runs no
 * engine, so the port it was wired with answers every call by throwing, and a
 * webui client that had just listed a row got a 500 for renaming, forking,
 * archiving or deleting it. The transcript is addressable by bare id from the
 * configuration home, so the gateway answers all four itself.
 *
 * <p>Three properties are load-bearing and each has a case here: an unused
 * local port falls through, a local port that <em>can</em> answer keeps its
 * answer, and an unknown id stays distinguishable from a session that exists
 * and an operation that failed on it.
 */
class TranscriptSessionActionsTest {

    private static final String UNMAPPED = "the wrapped port was never configured";

    private Path home;
    private String sourceId;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createTempDirectory(Path.of("/tmp"), "tsa");
        sourceId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteTree(home);
    }

    // --------------------------------------------------------- happy paths

    @Test
    void renameAppendsACustomTitleToTheTranscript() throws IOException {
        Path transcript = writeTranscript(sourceId);

        new TranscriptSessionActions(unmapped(), home).rename(sourceId, "a better name");

        assertThat(Files.readString(transcript))
            .contains("\"type\":\"custom-title\"")
            .contains("\"customTitle\":\"a better name\"")
            .contains(sourceId);
    }

    @Test
    void archiveAppendsTheArchivedMarkerWithoutTouchingTheRows() throws IOException {
        Path transcript = writeTranscript(sourceId);
        String before = Files.readString(transcript);

        new TranscriptSessionActions(unmapped(), home).archive(sourceId);

        String after = Files.readString(transcript);
        assertThat(after).startsWith(before);
        assertThat(after).contains("\"type\":\"archived\"").contains("\"archived\":true");
    }

    /**
     * A fork is a transcript derivation, not an engine operation: the child is
     * a new file beside its parent, carrying remapped ids and a title, and the
     * source transcript is left exactly as it was.
     */
    @Test
    void forkWritesAChildTranscriptBesideItsParent() throws IOException {
        Path transcript = writeTranscript(sourceId);
        String before = Files.readString(transcript);

        GatewaySessionActionsPort.ForkResult forked =
            new TranscriptSessionActions(unmapped(), home).fork(sourceId, null);

        assertThat(forked.sessionId()).isNotEqualTo(sourceId);
        Path child = transcript.resolveSibling(forked.sessionId() + ".jsonl");
        assertThat(child).exists();
        String derived = Files.readString(child);
        assertThat(derived).contains("\"sessionId\":\"" + forked.sessionId() + "\"");
        assertThat(derived).contains("\"customTitle\"");
        assertThat(derived).contains("\"forkedFrom\"");
        assertThat(Files.readString(transcript)).isEqualTo(before);
    }

    /** Delete removes the transcript and the sidecar directory named after it. */
    @Test
    void deleteRemovesTheTranscriptAndItsSidecarDirectory() throws IOException {
        Path transcript = writeTranscript(sourceId);
        Path sidecar = transcript.getParent().resolve(sourceId);
        Files.createDirectories(sidecar);
        Files.writeString(sidecar.resolve("state.json"), "{}\n");

        boolean deleted = new TranscriptSessionActions(unmapped(), home).delete(sourceId);

        assertThat(deleted).isTrue();
        assertThat(transcript).doesNotExist();
        assertThat(sidecar).doesNotExist();
    }

    // ------------------------------------------------------- unknown ids

    /**
     * An id no transcript answers to must reach the handler as
     * {@code UnknownSessionException}, which it renders as 404 — never as a
     * 500 inviting the user to retry a row that is gone.
     */
    @Test
    void anUnknownSessionIsReportedAsUnknownOnEveryAction() {
        TranscriptSessionActions actions = new TranscriptSessionActions(unmapped(), home);
        String missing = UUID.randomUUID().toString();

        assertThatThrownBy(() -> actions.rename(missing, "name"))
            .isInstanceOf(GatewaySessionActionsPort.UnknownSessionException.class)
            .hasMessageContaining(missing);
        assertThatThrownBy(() -> actions.fork(missing, null))
            .isInstanceOf(GatewaySessionActionsPort.UnknownSessionException.class)
            .hasMessageContaining(missing);
        assertThatThrownBy(() -> actions.archive(missing))
            .isInstanceOf(GatewaySessionActionsPort.UnknownSessionException.class)
            .hasMessageContaining(missing);
    }

    /**
     * Delete has no exception form for an absent row: the port documents
     * {@code false}, which the handler renders as the same 404. Two clients on
     * one catalog listing race and the loser got the outcome it asked for.
     */
    @Test
    void deletingAnUnknownSessionAnswersFalseRatherThanThrowing() {
        assertThat(new TranscriptSessionActions(unmapped(), home).delete(UUID.randomUUID().toString()))
            .isFalse();
    }

    /**
     * A session that resolves but cannot be forked — every row is sidechain —
     * is <em>not</em> unknown: the transcript is right there and the operation
     * failed on it. Collapsing this into 404 would tell the user their
     * conversation is gone when it is intact.
     */
    @Test
    void aFailureOnASessionThatExistsIsNotReportedAsUnknown() throws IOException {
        Path project = Files.createDirectories(home.resolve("projects").resolve("-work"));
        Files.writeString(project.resolve(sourceId + ".jsonl"),
            "{\"type\":\"user\",\"uuid\":\"" + UUID.randomUUID() + "\",\"sessionId\":\"" + sourceId
                + "\",\"isSidechain\":true,\"message\":{\"role\":\"user\"}}\n");

        assertThatThrownBy(() -> new TranscriptSessionActions(unmapped(), home).fork(sourceId, null))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(GatewaySessionActionsPort.UnknownSessionException.class);
    }

    // ----------------------------------------------- local-first dispatch

    /**
     * The wrapped port is consulted first and its answer wins: a session this
     * process hosts is edited by its own engine, and the transcript on disk is
     * only the fallback for one it does not.
     */
    @Test
    void aLocalAnswerWinsOverTheTranscript() throws IOException {
        writeTranscript(sourceId);
        RecordingLocal local = new RecordingLocal();

        GatewaySessionActionsPort actions = new TranscriptSessionActions(local, home);
        actions.rename(sourceId, "renamed");
        assertThat(actions.fork(sourceId, null).sessionId()).isEqualTo(RecordingLocal.FORKED);
        actions.archive(sourceId);
        assertThat(actions.delete(sourceId)).isTrue();

        assertThat(local.calls).containsExactly("rename", "fork", "archive", "delete");
        assertThat(Files.readString(transcriptOf(sourceId))).doesNotContain("custom-title");
    }

    /**
     * The local port's own verdicts pass through untouched, including its 404
     * and its 500: those describe a live operation, and re-answering them from
     * a transcript would be a second, divergent opinion about the same session.
     */
    @Test
    void aLocalFailureIsNotRetriedAgainstTheTranscript() throws IOException {
        Path transcript = writeTranscript(sourceId);
        RecordingLocal local = new RecordingLocal();
        local.failure = new GatewaySessionActionsPort.UnknownSessionException("host says gone");

        assertThatThrownBy(() -> new TranscriptSessionActions(local, home).rename(sourceId, "x"))
            .isInstanceOf(GatewaySessionActionsPort.UnknownSessionException.class)
            .hasMessageContaining("host says gone");
        assertThat(Files.readString(transcript)).doesNotContain("custom-title");
    }

    /**
     * A local port that is not configured is the only signal that says "ask the
     * transcript instead". It is a {@code RuntimeException}, so a decorator that
     * merely caught and re-threw would leave the standalone gateway answering
     * 500 for every row it had just listed — the reported bug's exact wording,
     * asserted here against the unwrapped interface so the case cannot drift
     * away from the symptom it is about.
     */
    @Test
    void anUnconfiguredLocalPortFallsThroughToTheTranscript() throws IOException {
        Path transcript = writeTranscript(sourceId);
        GatewaySessionActionsPort unmapped = new GatewaySessionActionsPort() {};

        assertThatThrownBy(() -> unmapped.rename(sourceId, "x"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("is not configured");

        new TranscriptSessionActions(unmapped, home).rename(sourceId, "x");

        assertThat(Files.readString(transcript)).contains("\"customTitle\":\"x\"");
    }

    /**
     * The configuration home a gateway derives is the one its worker directory
     * sits in, so both the standalone gateway's {@code ~/.claude} and a test
     * that relocates state through {@code useWebStateDirectory} resolve the same
     * {@code projects/} tree the other readers use.
     */
    @Test
    void theConfigurationHomeIsTheWorkerDirectorysParent() {
        assertThat(TranscriptSessionActions.configurationHomeOf(
                new WebWorkerDirectory(home.resolve("web-workers"))))
            .isEqualTo(home);
    }

    // ------------------------------------------------------------ fixtures

    /** The port a standalone gateway is wired with: every call throws. */
    private static GatewaySessionActionsPort unmapped() {
        return new GatewaySessionActionsPort() {
            @Override public void rename(String sessionId, String title) {
                throw new UnsupportedOperationException(UNMAPPED);
            }

            @Override public ForkResult fork(String sessionId, String title) {
                throw new UnsupportedOperationException(UNMAPPED);
            }

            @Override public void archive(String sessionId) {
                throw new UnsupportedOperationException(UNMAPPED);
            }

            @Override public boolean delete(String sessionId) {
                throw new UnsupportedOperationException(UNMAPPED);
            }
        };
    }

    /** A local port that answers and records what it was asked. */
    private static final class RecordingLocal implements GatewaySessionActionsPort {
        static final String FORKED = UUID.randomUUID().toString();

        final List<String> calls = new ArrayList<>();
        RuntimeException failure;

        private void record(String call) {
            calls.add(call);
            if (failure != null) throw failure;
        }

        @Override public void rename(String sessionId, String title) {
            record("rename");
        }

        @Override public ForkResult fork(String sessionId, String title) {
            record("fork");
            return new ForkResult(FORKED);
        }

        @Override public void archive(String sessionId) {
            record("archive");
        }

        @Override public boolean delete(String sessionId) {
            record("delete");
            return true;
        }
    }

    /**
     * One session's transcript under {@code <home>/projects/<project>/}, the
     * layout {@code SessionOperationsService} resolves a bare id against.
     */
    private Path writeTranscript(String sessionId) throws IOException {
        Path project = Files.createDirectories(home.resolve("projects").resolve("-work"));
        Path transcript = project.resolve(sessionId + ".jsonl");
        String user = UUID.randomUUID().toString();
        String assistant = UUID.randomUUID().toString();
        Files.writeString(transcript,
            "{\"type\":\"user\",\"uuid\":\"" + user + "\",\"parentUuid\":null,\"sessionId\":\""
                + sessionId + "\",\"message\":{\"role\":\"user\",\"content\":\"hello\"},"
                + "\"timestamp\":\"2026-01-01T00:00:00Z\"}\n"
                + "{\"type\":\"assistant\",\"uuid\":\"" + assistant + "\",\"parentUuid\":\""
                + user + "\",\"sessionId\":\"" + sessionId + "\",\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]},"
                + "\"timestamp\":\"2026-01-01T00:00:01Z\"}\n",
            StandardCharsets.UTF_8);
        return transcript;
    }

    private Path transcriptOf(String sessionId) {
        return home.resolve("projects").resolve("-work").resolve(sessionId + ".jsonl");
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }
}