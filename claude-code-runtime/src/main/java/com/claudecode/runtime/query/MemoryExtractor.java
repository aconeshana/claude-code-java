package com.claudecode.runtime.query;


import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Engine-lifecycle hook for background extraction of durable facts from the current conversation
 * into the auto-memory directory.
 */
public interface MemoryExtractor {

    /**
     * @param messagesSinceLastTurn the full current message list (the extractor
     *                              tracks its own cursor across calls)
     * @param engine                the owning engine — supplies model/tools/workingDirectory,
     *                              and is the target of {@link DefaultQuerySession#queueNotification}
     */
    void extractAsync(List<Message> messagesSinceLastTurn, QuerySession engine);

    /**
     * Awaits any in-flight extraction (including a stashed trailing run) with a soft timeout, so a
     * background extraction isn't killed mid-write by process exit.
     */
    void drainPending(long timeoutMillis);
}
