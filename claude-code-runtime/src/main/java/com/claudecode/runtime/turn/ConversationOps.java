package com.claudecode.runtime.turn;

import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.MessageConstants;

/**
 * Narrow port for the two conversation-history operations {@link TurnEngine} performs on an
 * interrupt auto-restore.
 */
public interface ConversationOps {

/**
     * Pop the last entry from the persistent prompt history.
     */
    void dropLastPromptHistoryEntry();

    /**
     * Rewind the conversation to before the last real user message and return the removed {@link
     * UserMessage} (or null).
     */
    UserMessage rewindBeforeLastRealUser();

    /**
     * Text to put back into the adapter after {@code message} is removed. Adapters may override
     * this when their prompt surface has mode-specific source formatting.
     */
    default String restoredInput(UserMessage message) {
        return message == null ? null : MessageConstants.getContentText(message.message());
    }
}
