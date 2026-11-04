package com.claudecode.gateway;

/**
 * Consumer-owned boundary for stopping the active turn on one session — the
 * remote twin of the TUI's Ctrl+C and the SDK's {@code interrupt} control
 * request, feeding the webui composer's stop button.
 *
 * <p>Addressing mirrors {@link GatewayPermissionModePort}: a blank session id
 * means the active TUI session, and a known headless id means that open
 * headless session. Cancellation has no read-only transcript projection — a
 * closed session has no live turn to stop — so an id no live session answers
 * to is reported as absent rather than refused.
 */
@FunctionalInterface
public interface GatewaySessionCancelPort {

    /**
     * Requests cancellation of the addressed session's active turn.
     *
     * @return whether a live session was reached. {@code false} means the id
     *         resolved to no runnable session; the turn state is unknown but
     *         nothing was asked to stop, which the caller reports as 404.
     */
    boolean cancel(String sessionId);
}
