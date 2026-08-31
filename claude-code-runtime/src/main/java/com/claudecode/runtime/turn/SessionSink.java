package com.claudecode.runtime.turn;

import com.claudecode.core.message.SDKMessage;

/**
 * The output port a front-end implements to render one session's turn stream.
 */
public interface SessionSink {

    /** A turn is starting for {@code input}; echo it. Synchronous, on the submitting thread. */
    void onTurnStart(UserInput input);

    /** One streamed domain message, in order, on the turn's background thread. */
    void onMessage(SDKMessage msg);

    /**
     * The turn's stream aborted. {@code userCancel} is true for a user interrupt
     * (no error line shown); otherwise {@code error} carries the failure to display.
     * On the turn's background thread.
     */
    void onError(Throwable error, boolean userCancel);

    /**
     * The turn finished; the engine has already applied the domain side (history pop,
     * rewind, permission-mode restore). Render summary / interrupt line / auto-restore
     * from {@code outcome}. Turn-scoped permission/hook cleanup follows on the publish
     * continuation. On the turn's background thread.
     */
    void onTurnComplete(TurnOutcome outcome);

    /** The queue drained with no follow-up turn — the session is idle. On the publish thread. */
    void onIdle();
}
