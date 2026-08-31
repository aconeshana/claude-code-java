package com.claudecode.core.engine;

/**
 * Sink for persisting {@link FileHistoryManager.Snapshot}s to a session transcript.
 */
public interface FileHistorySnapshotSink {

    /**
     * Persist a snapshot for {@code messageId}.
     *
     * @param sessionId        active session id
     * @param messageId        the user message the snapshot belongs to
     * @param snapshot         the snapshot to record
     * @param isSnapshotUpdate {@code true} when this replaces the most recent
     *                         snapshot's backup map (a {@link FileHistoryManager#trackEdit}
     *                         retroactive addition), {@code false} for a brand
     *                         new snapshot (a {@link FileHistoryManager#makeSnapshot} call)
     */
    void record(String sessionId, String messageId, FileHistoryManager.Snapshot snapshot, boolean isSnapshotUpdate);
}
