package com.claudecode.core.engine;

import com.claudecode.core.message.SDKMessage;

import java.util.Iterator;

/**
 * Exposes the SDK-side-event sequence captured when the most recently returned
 * main query message was emitted.
 *
 * <ul>
 *   <li>each synchronous
 *       {@code drainSdkEvents; output.enqueue(message)} slice observes only
 *       side events that existed before that main message was yielded.</li>
 *   <li>FIFO SDK lifecycle-event ordering.</li>
 * </ul>
 *
 * <p>The query producer and stdout consumer run on different virtual threads.
 * Carrying this boundary preserves drain-before-output ordering when a
 * background agent emits a side event after a parent tool result enters the
 * main iterator but before stdout consumes it.
 */
public interface SdkEventSequencedIterator extends Iterator<SDKMessage> {

    /** Sequence visible immediately before the last returned main message was queued. */
    long sdkEventSequenceForLastMessage();
}
