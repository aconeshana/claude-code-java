package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The turn facts a headless session reports when a web-composed turn ends.
 *
 * <p>Regression cover for every web-opened session's turn tail reading
 * {@code 用时 0秒}: the driver completed each turn with a constant zero
 * elapsed time instead of the turn's wall clock.
 */
class HeadlessTurnDriverTest {

    private static final long TURN_MILLIS = 60;

    @Test
    @Timeout(10)
    void aCompletedTurnReportsItsWallClock() throws Exception {
        CompletableFuture<TurnOutcome> completed = new CompletableFuture<>();
        SessionEventHub events = new SessionEventHub(new SessionSink() {
            @Override public void onTurnStart(UserInput input) { }
            @Override public void onMessage(SDKMessage msg) { }
            @Override public void onError(Throwable error, boolean userCancel) {
                completed.completeExceptionally(error);
            }
            @Override public void onTurnComplete(TurnOutcome outcome) {
                completed.complete(outcome);
            }
            @Override public void onIdle() { }
        }, null);

        new HeadlessTurnDriver(engineTakingAWhile(), events, null)
            .submit(new SessionHostSubmission("hello", null, null, null));

        TurnOutcome outcome = completed.get(5, TimeUnit.SECONDS);
        assertThat(outcome.elapsedMs()).isGreaterThanOrEqualTo(TURN_MILLIS);
        assertThat(outcome.userCancel()).isFalse();
    }

    /** An engine whose single turn streams nothing and takes {@link #TURN_MILLIS}. */
    private static QuerySession engineTakingAWhile() {
        Iterator<SDKMessage> slowEmptyTurn = new Iterator<>() {
            private boolean waited;

            @Override public boolean hasNext() {
                if (!waited) {
                    waited = true;
                    try {
                        Thread.sleep(TURN_MILLIS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                return false;
            }

            @Override public SDKMessage next() {
                throw new NoSuchElementException();
            }
        };
        QuerySession.Submission submission = proxy(QuerySession.Submission.class,
            "submitMessage", slowEmptyTurn);
        return proxy(QuerySession.class, "submission", submission);
    }

    /** A proxy answering one method; any other call is a test defect. */
    private static <T> T proxy(Class<T> type, String method, Object answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (_, called, _) -> {
                if (called.getName().equals(method)) return answer;
                throw new UnsupportedOperationException(called.getName());
            }));
    }
}
