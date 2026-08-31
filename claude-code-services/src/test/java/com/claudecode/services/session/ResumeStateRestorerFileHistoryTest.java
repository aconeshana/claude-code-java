package com.claudecode.services.session;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeStateRestorerFileHistoryTest {

    @TempDir Path tempDir;
    @TempDir Path backupRoot;

    private SessionStorage storage;
    private SessionManager sessionManager;

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    @BeforeEach
    void setUp() {
        storage = new SessionStorage();
        sessionManager = new SessionManager(tempDir, tempDir.toString());
    }

    private DefaultQuerySession newEngineWithFileHistory(SessionIdentity identity) {
        FileHistoryManager fhm = new FileHistoryManager(identity, tempDir, backupRoot);
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .sessionIdentity(identity)
            .initialFileHistoryManager(fhm)
            .build();
        return new DefaultQuerySession(config);
    }

    @Test
    void postSwitch_restoresFileHistorySnapshotsFromLog() throws Exception {
        SessionIdentity identity = SessionIdentity.of("session-a");
        DefaultQuerySession engine = newEngineWithFileHistory(identity);
        Path sessionFile = sessionManager.getSessionFile("session-a");

        // Simulate a previous run: file edited + tracked + a snapshot recorded to the JSONL.
        Path f = tempDir.resolve("a.txt");
        Files.writeString(f, "hello");
        FileHistoryManager fhm = engine.getFileHistoryManager();
        fhm.setSnapshotSink(new SessionFileHistorySink(storage, sessionManager));
        fhm.makeSnapshot("msg-1");
        fhm.trackEdit(f.toString());

        // Fresh manager/engine simulating a process restart — nothing in memory.
        DefaultQuerySession resumedEngine = newEngineWithFileHistory(identity);
        ResumeStateRestorer restorer = new ResumeStateRestorer(resumedEngine, storage, null, null);

        restorer.postSwitch(sessionFile, List.<Message>of(), tempDir.toString());

        assertTrue(resumedEngine.getFileHistoryManager().canRestore("msg-1"));
    }

    @Test
    void postSwitch_thenRewind_worksAfterResume() throws Exception {
        SessionIdentity identity = SessionIdentity.of("session-b");
        DefaultQuerySession engine = newEngineWithFileHistory(identity);
        Path sessionFile = sessionManager.getSessionFile("session-b");

        Path f = tempDir.resolve("b.txt");
        Files.writeString(f, "original content");
        FileHistoryManager fhm = engine.getFileHistoryManager();
        fhm.setSnapshotSink(new SessionFileHistorySink(storage, sessionManager));
        fhm.makeSnapshot("msg-1");
        fhm.trackEdit(f.toString());

        Files.writeString(f, "modified content");
        fhm.makeSnapshot("msg-2");

        // Resume in what stands in for a new process — a fresh engine/manager
// with no in-memory state, same session id (matches /resume, which
        // does not mint a new session id — see ResumeStateRestorer#restoreFileHistory).
        DefaultQuerySession resumedEngine = newEngineWithFileHistory(identity);
        ResumeStateRestorer restorer = new ResumeStateRestorer(resumedEngine, storage, null, null);
        restorer.postSwitch(sessionFile, List.<Message>of(), tempDir.toString());

        resumedEngine.getFileHistoryManager().rewind("msg-1");

        assertEquals("original content", Files.readString(f),
            "rewind must work after resume, not just in the original process");
    }

    @Test
    void postSwitch_noFileHistorySnapshots_doesNotThrow() {
        SessionIdentity identity = SessionIdentity.of("session-c");
        DefaultQuerySession engine = newEngineWithFileHistory(identity);
        Path sessionFile = sessionManager.getSessionFile("session-c");
        ResumeStateRestorer restorer = new ResumeStateRestorer(engine, storage, null, null);

        assertDoesNotThrow(() -> restorer.postSwitch(sessionFile, List.<Message>of(), tempDir.toString()));
        assertTrue(engine.getFileHistoryManager().snapshotsView().isEmpty());
    }

    @Test
    void postSwitch_fileHistoryDisabled_doesNotThrow() {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .build(); // fileHistoryEnabled defaults to false -> getFileHistoryManager is null
        DefaultQuerySession engine = new DefaultQuerySession(config);
        ResumeStateRestorer restorer = new ResumeStateRestorer(engine, storage, null, null);
        Path sessionFile = sessionManager.getSessionFile("session-d");

        assertNull(engine.getFileHistoryManager());
        assertDoesNotThrow(() -> restorer.postSwitch(sessionFile, List.<Message>of(), tempDir.toString()));
    }
}
