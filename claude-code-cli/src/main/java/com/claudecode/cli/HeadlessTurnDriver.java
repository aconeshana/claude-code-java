package com.claudecode.cli;

import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Bridges gateway submissions onto one headless {@code QuerySession}.
 *
 * <p>{@code onTurnStart} fires synchronously on the submitting thread (the
 * gateway subscribes its turn-scoped sink before submitting, and the opening
 * event must not race the subscription). The engine's message iterator is
 * then drained on a virtual thread — an undrained iterator would stall the
 * turn — re-emitting every message through the session hub so protocol
 * projections and future mirrors observe the whole turn.
 */
final class HeadlessTurnDriver {

    private final QuerySession engine;
    private final SessionEventHub events;

    HeadlessTurnDriver(QuerySession engine, SessionEventHub events) {
        this.engine = engine;
        this.events = events;
    }

    /** Runs one turn; the returned stage completes when the turn ends. */
    CompletionStage<Void> submit(SessionHostSubmission submission) {
        String prompt = submission.prompt();
        CompletableFuture<Void> done = new CompletableFuture<>();
        // Synchronous on the caller thread: the gateway's turn-scoped
        // subscription must observe onTurnStart before any message.
        events.onTurnStart(UserInput.of(prompt, prompt, null, "default"));
        Thread.ofVirtual().name("headless-turn", 0).start(() -> {
            boolean userCancel = false;
            try {
                Iterator<SDKMessage> messages = engine.submission()
                    .submitMessage(prompt, SubmitOptions.of("user"));
                while (messages.hasNext()) {
                    SDKMessage message = messages.next();
                    if (message instanceof SDKMessage.Error(Exception error)) {
                        userCancel = engine.execution().getAbortController() != null
                            && engine.execution().getAbortController().isAborted();
                        events.onError(error, userCancel);
                        done.complete(null);
                        return;
                    }
                    events.onMessage(message);
                }
                events.onTurnComplete(turnOutcome());
                done.complete(null);
            } catch (RuntimeException failure) {
                events.onError(failure, false);
                done.complete(null);
            }
        });
        return done;
    }

    private static TurnOutcome turnOutcome() {
        return new TurnOutcome(false, false, false, false, false, 0L, null, null, null, null);
    }

    /** Message-level prior conversation for a resumed session; may be empty. */
    record PriorConversation(List<Message> messages) {
        PriorConversation {
            messages = List.copyOf(messages);
        }
    }
}
