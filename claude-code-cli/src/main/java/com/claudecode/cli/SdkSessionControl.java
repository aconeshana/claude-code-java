package com.claudecode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;

/**
 * Session-level control operations (context usage, file rewind, task/message lifecycle,
 * session title generation, and side questions) exposed through the SDK control protocol.
 */
interface SdkSessionControl {

    JsonNode contextUsage();

    SdkControlRuntime.RewindFilesResult rewindFiles(String userMessageId, boolean dryRun);

    boolean cancelAsyncMessage(String messageUuid);

    void seedReadState(String path, long mtime);

    void stopTask(String taskId);

    default boolean backgroundTasks(String toolUseId) {
        throw new UnsupportedOperationException("Backgrounding tasks is unavailable");
    }

    /** Completes deferred initialize-time work that must run off the stdin reader. */
    default void prepareForTurn() {
        // Most runtimes have no deferred first-turn work.
    }

    default CompletableFuture<String> generateSessionTitle(String description, boolean persist) {
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("SDK session title generation is unavailable"));
    }

    default CompletableFuture<String> sideQuestion(String question) {
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("SDK side questions are unavailable"));
    }
}
