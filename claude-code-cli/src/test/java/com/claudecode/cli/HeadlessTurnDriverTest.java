package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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

    @Test
    @Timeout(10)
    void apiErrorIsPersistedToTranscriptWhenNoAssistantErrorWasRecorded() throws Exception {
        CompletableFuture<Throwable> reported = new CompletableFuture<>();
        SessionEventHub events = new SessionEventHub(new SessionSink() {
            @Override public void onTurnStart(UserInput input) { }
            @Override public void onMessage(SDKMessage msg) { }
            @Override public void onError(Throwable error, boolean userCancel) {
                reported.complete(error);
            }
            @Override public void onTurnComplete(TurnOutcome outcome) { }
            @Override public void onIdle() { }
        }, null);
        List<Message> recorded = new CopyOnWriteArrayList<>();
        TranscriptSink sink = (sessionId, message) -> recorded.add(message);

        QuerySession engine = engineYielding(
            List.of(new SDKMessage.Error(new IOException("API streaming connection timed out after 60000ms"))),
            new AbortController());

        new HeadlessTurnDriver(engine, events, null, () -> "bypassPermissions", sink, "cron-session-1")
            .submit(new SessionHostSubmission("[定时任务] ping", null, null, null))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        assertThat(reported.get(5, TimeUnit.SECONDS).getMessage())
            .contains("timed out after 60000ms");
        assertThat(recorded).hasSize(1);
        assertThat(recorded.getFirst()).isInstanceOf(AssistantMessage.class);
        AssistantMessage apiError = (AssistantMessage) recorded.getFirst();
        assertThat(apiError.isApiErrorMessage()).isTrue();
        assertThat(((TextBlock) apiError.message().content().getFirst()).text())
            .contains("timed out after 60000ms");
    }

    @Test
    @Timeout(10)
    void userCancelledErrorDoesNotPersistApiErrorRow() throws Exception {
        CompletableFuture<Boolean> wasUserCancel = new CompletableFuture<>();
        SessionEventHub events = new SessionEventHub(new SessionSink() {
            @Override public void onTurnStart(UserInput input) { }
            @Override public void onMessage(SDKMessage msg) { }
            @Override public void onError(Throwable error, boolean userCancel) {
                wasUserCancel.complete(userCancel);
            }
            @Override public void onTurnComplete(TurnOutcome outcome) { }
            @Override public void onIdle() { }
        }, null);
        List<Message> recorded = new CopyOnWriteArrayList<>();
        TranscriptSink sink = (sessionId, message) -> recorded.add(message);
        AbortController abort = new AbortController();
        abort.abort("user_cancelled");

        QuerySession engine = engineYielding(
            List.of(new SDKMessage.Error(new IOException("aborted"))),
            abort);

        new HeadlessTurnDriver(engine, events, null, () -> "default", sink, "session-abort")
            .submit(new SessionHostSubmission("stop", null, null, null))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        assertThat(wasUserCancel.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(recorded).isEmpty();
    }

    @Test
    @Timeout(10)
    void alreadyRecordedApiErrorIsNotDuplicatedOnSdkError() throws Exception {
        CompletableFuture<Throwable> reported = new CompletableFuture<>();
        SessionEventHub events = new SessionEventHub(new SessionSink() {
            @Override public void onTurnStart(UserInput input) { }
            @Override public void onMessage(SDKMessage msg) { }
            @Override public void onError(Throwable error, boolean userCancel) {
                reported.complete(error);
            }
            @Override public void onTurnComplete(TurnOutcome outcome) { }
            @Override public void onIdle() { }
        }, null);
        List<Message> recorded = new CopyOnWriteArrayList<>();
        TranscriptSink sink = (sessionId, message) -> recorded.add(message);
        AssistantMessage existing = MessageFactory.createAssistantAPIErrorMessage("API error");

        QuerySession engine = engineYielding(
            List.of(new SDKMessage.Assistant(existing, null),
                new SDKMessage.Error(new IOException("duplicate"))),
            new AbortController());

        new HeadlessTurnDriver(engine, events, null, () -> "default", sink, "session-dedup")
            .submit(new SessionHostSubmission("hi", null, null, null))
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);

        assertThat(reported.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(recorded).isEmpty();
    }

    private static QuerySession engineYielding(List<SDKMessage> items, AbortController abort) {
        List<SDKMessage> copy = new ArrayList<>(items);
        QuerySession.Submission submission = proxy(QuerySession.Submission.class,
            "submitMessage", copy.iterator());
        QuerySession.Execution execution = proxy(QuerySession.Execution.class,
            "getAbortController", abort);
        return (QuerySession) Proxy.newProxyInstance(
            QuerySession.class.getClassLoader(),
            new Class<?>[] {QuerySession.class},
            (_, called, _) -> switch (called.getName()) {
                case "submission" -> submission;
                case "execution" -> execution;
                default -> throw new UnsupportedOperationException(called.getName());
            });
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
