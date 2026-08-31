package com.claudecode.http;


/**
 * Neutral cancellation hook used by the HTTP module without depending on the
 * core engine's {@code AbortController} type.
 *
 * <ul>
 *   <li>propagates an AbortSignal into
 *       initial API fetches and streaming connections.</li>
 *   <li>aborts in-flight fetches when
 *       the owning tool invocation is cancelled.</li>
 * </ul>
 */
@FunctionalInterface
public interface CancellationRegistrar {

    CancellationRegistrar NONE = _ -> () -> { };

    /** Registers an action and returns a handle that removes the registration. */
    AutoCloseable register(Runnable cancelAction);
}
