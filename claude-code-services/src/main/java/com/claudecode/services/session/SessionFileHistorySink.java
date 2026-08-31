package com.claudecode.services.session;

import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.core.engine.FileHistorySnapshotSink;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists {@link FileHistoryManager.Snapshot}s to the active session's JSONL transcript.
 */
public final class SessionFileHistorySink implements FileHistorySnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(SessionFileHistorySink.class);

    private final SessionStorage storage;
    private final SessionManager sessionManager;

    public SessionFileHistorySink(SessionStorage storage, SessionManager sessionManager) {
        this.storage = storage;
        this.sessionManager = sessionManager;
    }

    @Override
    public void record(String sessionId, String messageId, FileHistoryManager.Snapshot snapshot, boolean isSnapshotUpdate) {
        try {
            storage.insertFileHistorySnapshot(sessionManager.getSessionFile(sessionId), messageId,
                FileHistorySnapshotCodec.toJson(snapshot), isSnapshotUpdate);
        } catch (Exception e) {
            log.warn("Failed to record file-history snapshot for session {}: {}", sessionId, e.getMessage());
        }
    }
}
