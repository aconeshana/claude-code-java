package com.claudecode.services.session;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.session.SessionManager;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.tools.worktree.WorktreeSession;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class ResumeStateRestorerWorktreeTest {

    @TempDir Path tempDir;

    private SessionStorage storage;
    private SessionManager sessionManager;
    private String savedUserDir;

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    @BeforeEach
    void setUp() {
        storage = new SessionStorage(JsonUtils.getMapper());
        sessionManager = new SessionManager(tempDir, tempDir.toString());
        savedUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void tearDown() {
        WorktreeService.clearCurrentSessionForTests();
        if (savedUserDir != null) System.setProperty("user.dir", savedUserDir);
    }

    private DefaultQuerySession newEngine(String sessionId) {
        SessionIdentity identity = SessionIdentity.of(sessionId);
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory(tempDir.toString())
            .sessionIdentity(identity)
            .build();
        return new DefaultQuerySession(config);
    }

    @Test
    void exitCurrentWorktreeBeforeSwitch_noActiveWorktree_isNoOp() {
        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);
        assertDoesNotThrow(restorer::exitCurrentWorktreeBeforeSwitch);
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void exitCurrentWorktreeBeforeSwitch_activeWorktree_restoresCwdAndClearsSession() throws Exception {
        Path original = Files.createDirectory(tempDir.resolve("original"));
        Path worktree = Files.createDirectory(tempDir.resolve("worktree"));
        WorktreeSession session = new WorktreeSession(
            original.toString(), worktree.toString(), "feature-x", "worktree-feature-x",
            "main", "abc123", "s1", null, false, 0L, false);
        WorktreeService.restoreWorktreeSession(session);
        System.setProperty("user.dir", worktree.toString());

        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);
        restorer.exitCurrentWorktreeBeforeSwitch();

        assertNull(WorktreeService.getCurrentWorktreeSession());
        assertEquals(original.toString(), System.getProperty("user.dir"));
    }

    @Test
    void restoreWorktreeState_noEntry_isNoOp() {
        Path sessionFile = sessionManager.getSessionFile("s1");
        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);

        assertDoesNotThrow(() -> restorer.restoreWorktreeState(sessionFile));
        assertNull(WorktreeService.getCurrentWorktreeSession());
    }

    @Test
    void restoreWorktreeState_directoryStillExists_switchesCwdAndPopulatesService() throws Exception {
        Path worktree = Files.createDirectory(tempDir.resolve("worktree"));
        Path sessionFile = sessionManager.getSessionFile("s1");
        ObjectNode json = JsonUtils.getMapper().createObjectNode();
        json.put("originalCwd", tempDir.toString());
        json.put("worktreePath", worktree.toString());
        json.put("worktreeName", "feature-x");
        json.put("worktreeBranch", "worktree-feature-x");
        storage.appendWorktreeState(sessionFile, "s1", json);

        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);
        restorer.restoreWorktreeState(sessionFile);

        assertEquals(worktree.toString(), System.getProperty("user.dir"));
        WorktreeSession restored = WorktreeService.getCurrentWorktreeSession();
        assertNotNull(restored);
        assertEquals(worktree.toString(), restored.worktreePath());
        assertEquals("worktree-feature-x", restored.worktreeBranch());
    }

    @Test
    void restoreWorktreeState_directoryGone_recordsExitInsteadOfSwitching() throws Exception {
        Path sessionFile = sessionManager.getSessionFile("s1");
        ObjectNode json = JsonUtils.getMapper().createObjectNode();
        json.put("originalCwd", tempDir.toString());
        json.put("worktreePath", tempDir.resolve("no-longer-here").toString());
        json.put("worktreeName", "feature-x");
        storage.appendWorktreeState(sessionFile, "s1", json);

        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);
        restorer.restoreWorktreeState(sessionFile);

        assertNull(WorktreeService.getCurrentWorktreeSession());
        SessionStorage.WorktreeStateEntry entry = storage.scanWorktreeState(sessionFile);
        assertNotNull(entry);
        assertNull(entry.worktreeSessionJson(), "must record the exit instead of re-persisting a dead path");
    }

    @Test
    void postSwitch_wiresWorktreeRestoreAutomatically() throws Exception {
        Path worktree = Files.createDirectory(tempDir.resolve("worktree"));
        Path sessionFile = sessionManager.getSessionFile("s1");
        ObjectNode json = JsonUtils.getMapper().createObjectNode();
        json.put("originalCwd", tempDir.toString());
        json.put("worktreePath", worktree.toString());
        json.put("worktreeName", "feature-x");
        storage.appendWorktreeState(sessionFile, "s1", json);

        ResumeStateRestorer restorer = new ResumeStateRestorer(newEngine("s1"), storage, null, null);
        restorer.postSwitch(sessionFile, List.<Message>of(), tempDir.toString());

        assertEquals(worktree.toString(), System.getProperty("user.dir"));
        assertNotNull(WorktreeService.getCurrentWorktreeSession());
    }
}
