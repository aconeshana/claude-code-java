package com.claudecode.services.compact;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.Message;

import java.util.List;
import java.util.Set;

/**
 * Per-turn content compaction on every turn, before each API call.
 */
interface MicrocompactStrategy {

    /** Tool names whose results are eligible for the time-based trigger. */
    Set<String> COMPACTABLE_TOOLS = Set.of("Read", "Bash", "Grep", "Glob", "Edit", "Write");

    /**
     * Run microcompact on the given message list.
     */
    MessageCompactor.MicrocompactResult apply(List<Message> messages);

    /**
     * Like {@link #apply(List)} but for the live main-thread request path, where the time-based trigger
     * may fire first and short-circuit.
     */
    default MessageCompactor.MicrocompactResult apply(List<Message> messages, boolean liveMainThread) {
        return apply(messages);
    }
}
