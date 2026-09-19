package com.claudecode.core.engine;

import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Builds a one-off request that forks a live query session: the session's own
 * system prompt and tool catalog, its conversation as the cached prefix, and a
 * single extra user turn carrying {@code forkPrompt}.
 *
 * <p>This is the narrow, core-visible view of
 * {@code QuerySession.Forks#buildCacheSharingRequest} — every parameter and the
 * return type already live in this module, so a consumer that only needs to
 * fork (sub-agent compaction) can take this instead of a dependency on the
 * runtime session type. {@code engine.forks()::buildCacheSharingRequest} is a
 * conforming method reference.
 *
 * <p>Carrying the tool catalog is not optional: a conversation that already
 * contains {@code tool_use} blocks is only a valid request when the tools are
 * declared alongside it.
 */
@FunctionalInterface
public interface CacheSharingForkBuilder {

    /**
     * @param messages    conversation to fork, used verbatim as the cached prefix
     * @param forkPrompt  the instruction appended as a final user turn
     * @param querySource request-level label for the fork, e.g. {@code "compact"}
     * @return the fork request, carrying the session's system prompt and tools
     */
    StreamingClient.StreamRequest build(List<Message> messages, String forkPrompt,
                                        String querySource);
}
