package com.claudecode.core.queue;

/**
 * Whether a running tool use is cancelled by a mid-turn user submission.
 *
 * <p>The streaming executor re-evaluates this for every executing tool when the
 * user submits a new prompt while the turn is active: when every executing tool
 * reports {@link #CANCEL}, the submission aborts the current turn immediately
 * ("steer"); when any tool reports {@link #BLOCK}, the submission only queues
 * and the turn runs to completion first.
 *
 * <ul>
 *   <li>{@code Tool.ts} — optional {@code interruptBehavior?(): 'cancel' | 'block'}
 *       declaration on the tool interface, documented as defaulting to
 *       {@code 'block'} when not implemented.</li>
 * </ul>
 */
public enum InterruptBehavior {
    /** The tool use is cancelled when the user steers mid-turn. */
    CANCEL,
    /** The tool use runs to completion; a mid-turn submission only queues. */
    BLOCK
}
