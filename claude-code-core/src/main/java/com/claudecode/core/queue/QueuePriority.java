package com.claudecode.core.queue;

/**
 * Priority levels for queued commands.
 *
 * <ul>
 *   <li>{@code QueuePriority} union type</li>
 * </ul>
 *
 * <p>Processing order: {@code NOW > NEXT > LATER}. Within the same level,
 * commands are dequeued FIFO.
 */
public enum QueuePriority {
    /** Process immediately, before anything else. */
    NOW(0),
    /** Process before LATER (channel messages, user input). */
    NEXT(1),
    /** Process last (task notifications). */
    LATER(2);

    final int order;

    QueuePriority(int order) { this.order = order; }

    /** Numeric processing order — lower runs first ({@code NOW=0 < NEXT=1 < LATER=2}). */
    public int order() { return order; }
}
