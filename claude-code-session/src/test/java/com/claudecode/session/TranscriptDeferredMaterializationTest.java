package com.claudecode.session;

import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deferred session-file materialization, mirroring the upstream 197/236 transcript
 * writer: while a session has no first user/assistant/system row, metadata writes
 * (mode, permission-mode, metrics, last-prompt, …) stay in an in-memory buffer and
 * the JSONL file must not exist on disk.
 */
class TranscriptDeferredMaterializationTest {

    @TempDir
    Path tempDir;

    private SessionManager sessionManager;
    private TranscriptRecorder recorder;
    private SessionStorage storage;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(tempDir, "/test/project");
        storage = new SessionStorage();
        recorder = new TranscriptRecorder(sessionManager, storage);
    }

    @Test
    void metadataAloneDoesNotCreateTheSessionFile() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordMode(sessionId, "normal");
        recorder.recordPermissionMode(sessionId, "default");
        recorder.recordSessionMetrics(sessionId, metricsEvent(sessionId));
        recorder.recordLastPrompt(sessionId, "hello");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        assertFalse(Files.exists(sessionFile),
            "metadata-only writes must not materialize the session file");
    }

    @Test
    void firstUserMessageMaterializesFileAndFlushesBufferedMetadata() throws Exception {
        String sessionId = sessionManager.createSession();

        recorder.recordMode(sessionId, "normal");
        recorder.recordPermissionMode(sessionId, "default");
        assertFalse(Files.exists(sessionManager.getSessionFile(sessionId)));

        recorder.recordTranscript(sessionId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("hello")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        Path sessionFile = sessionManager.getSessionFile(sessionId);
        assertTrue(Files.exists(sessionFile));
        List<String> types = JsonUtils.readJsonLines(sessionFile).stream()
            .map(line -> line.path("type").asText()).toList();
        assertEquals(List.of("mode", "permission-mode", "user"), types,
            "buffered metadata must flush ahead of the first message, in order");
    }

    @Test
    void clearedSessionLeftEmptyByExitStaysOffDisk() throws Exception {
        String parentId = sessionManager.createSession();
        recorder.recordTranscript(parentId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("before")));
        assertTrue(recorder.awaitPendingWrites(parentId, 5_000));
        assertTrue(recorder.releaseSessionState(parentId, 5_000));

        // /clear: engine regenerates the id; the new session only receives
        // lineage metadata before the user exits without typing anything.
        String clearedId = sessionManager.createSession();
        recorder.recordMode(clearedId, "normal");
        recorder.recordPermissionMode(clearedId, "default");
        recorder.appendParentSession(clearedId, parentId, "clear");
        assertTrue(recorder.awaitPendingWrites(clearedId, 5_000));

        Path clearedFile = sessionManager.getSessionFile(clearedId);
        assertFalse(Files.exists(clearedFile),
            "a /clear successor with no conversation must not leave an empty file");
    }

    @Test
    void clearedSessionThatContinuesMaterializesParentSessionRow() throws Exception {
        String parentId = sessionManager.createSession();
        recorder.recordTranscript(parentId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("before")));
        assertTrue(recorder.awaitPendingWrites(parentId, 5_000));
        assertTrue(recorder.releaseSessionState(parentId, 5_000));

        String clearedId = sessionManager.createSession();
        recorder.appendParentSession(clearedId, parentId, "clear");
        recorder.recordMode(clearedId, "normal");
        recorder.recordTranscript(clearedId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("after")));
        assertTrue(recorder.awaitPendingWrites(clearedId, 5_000));

        Path clearedFile = sessionManager.getSessionFile(clearedId);
        assertTrue(Files.exists(clearedFile));
        List<JsonNode> lines = JsonUtils.readJsonLines(clearedFile);
        JsonNode parentRow = lines.stream()
            .filter(line -> Strings.CS.equals("parent-session", line.path("type").asText()))
            .findFirst().orElseThrow();
        assertEquals(parentId, parentRow.path("parentSessionId").asText());
        assertEquals("clear", parentRow.path("relation").asText());
    }

    @Test
    void releaseWithoutMaterializationDropsBufferedMetadata() throws Exception {
        String sessionId = sessionManager.createSession();
        recorder.recordMode(sessionId, "normal");
        recorder.recordLastPrompt(sessionId, "unanswered");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        assertTrue(recorder.releaseSessionState(sessionId, 5_000));
        assertFalse(Files.exists(sessionManager.getSessionFile(sessionId)),
            "released un-materialized session must not flush its buffer to disk");
    }

    @Test
    void resumedExistingSessionWritesMetadataImmediately() throws Exception {
        String sessionId = sessionManager.createSession();
        Path sessionFile = sessionManager.getSessionFile(sessionId);
        // Pre-existing transcript: the file is already materialized upstream-side.
        recorder.recordTranscript(sessionId,
            new UserMessage(UUID.randomUUID().toString(), MessageContent.ofText("existing")));
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));
        assertTrue(recorder.releaseSessionState(sessionId, 5_000));

        recorder.recordMode(sessionId, "normal");
        assertTrue(recorder.awaitPendingWrites(sessionId, 5_000));

        List<JsonNode> lines = JsonUtils.readJsonLines(sessionFile);
        assertEquals("mode", lines.getLast().path("type").asText(),
            "a session whose file already exists must not buffer metadata");
    }

    private static SessionMetricsEvent metricsEvent(String sessionId) {
        return new SessionMetricsEvent(
            SessionMetricsEvent.SCHEMA_VERSION, 0, 0L, sessionId,
            SessionMetricsEvent.Kind.SESSION_START, null, 0, 0, null,
            0, 0, 0, 0, false);
    }
}

