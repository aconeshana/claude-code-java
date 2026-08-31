package com.claudecode.tools.tasks;

/**
 * Task lifecycle status.
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    KILLED;

    /**
     * Whether the task will never advance on its own again, so its output and final metadata can be
     * read back.
     */
    public boolean isTerminal() {
        return this == PAUSED || this == COMPLETED || this == FAILED || this == KILLED;
    }

    /**
     * Whether the task produced an outcome worth announcing to the model and may then be evicted.
     */
    public boolean hasResult() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }
}
