package com.claudecode.services.compact;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.Usage;

/**
 * Exception thrown when a compaction operation fails.
 */
public class CompactException extends RuntimeException
        implements MessageCompactor.UsageBearingFailure {

    private final Usage compactionUsage;

    public CompactException(String message) {
        this(message, Usage.EMPTY);
    }

    public CompactException(String message, Usage compactionUsage) {
        super(message);
        this.compactionUsage = compactionUsage != null ? compactionUsage : Usage.EMPTY;
    }

    @Override
    public Usage compactionUsage() {
        return compactionUsage;
    }
}
