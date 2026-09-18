package com.claudecode.runtime.turn;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.SDKMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cascading semantic output sink for one host session.
 */
@Explanation("Cascades one semantic session stream to local and remote sinks")
public final class SessionEventHub implements SessionSink {

    private static final Logger log = LoggerFactory.getLogger(SessionEventHub.class);
    private static final int MAX_REPLAY_EVENTS = 2_048;

    private final SessionSink primary;
    private final Consumer<RuntimeException> observerFailure;
    private final Object lock = new Object();
    private final List<SessionSink> observers = new ArrayList<>();
    private final List<Consumer<SessionSink>> replay = new ArrayList<>();

    public SessionEventHub(
            SessionSink primary, Consumer<RuntimeException> observerFailure) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.observerFailure = observerFailure != null ? observerFailure : _ -> {};
    }

    /**
     * Adds one observer. Closing the returned subscription is idempotent and
     * prevents callbacks that have not already entered an in-flight snapshot.
     */
    public AutoCloseable subscribe(SessionSink observer) {
        return subscribe(observer, true);
    }

    /**
     * Adds one observer, optionally skipping the recorded replay prefix.
     *
     * <p>Turn-scoped consumers — a gateway request that must observe exactly
     * one in-flight turn — subscribe with {@code replay=false}: the recorded
     * prefix belongs to a previous turn and would otherwise seed the stream
     * with stale events before the submitted turn starts.
     */
    public AutoCloseable subscribe(SessionSink observer, boolean replay) {
        Objects.requireNonNull(observer, "observer");
        if (observer == primary) {
            throw new IllegalArgumentException("primary sink cannot subscribe to itself");
        }
        synchronized (lock) {
            if (observers.contains(observer)) return () -> unsubscribe(observer);
            if (replay) {
                for (Consumer<SessionSink> callback : this.replay) notifyObserver(observer, callback);
            }
            observers.add(observer);
        }
        return () -> unsubscribe(observer);
    }

    /**
     * Discards the late-subscriber replay prefix when the application switches
     * to another logical session. Live observers stay attached because the TUI
     * reuses one semantic hub across {@code /clear}, {@code /new}, and resume.
     */
    public void resetReplay() {
        synchronized (lock) {
            replay.clear();
        }
    }

    @Override
    public void onTurnStart(UserInput input) {
        synchronized (lock) {
            notifyPrimary(sink -> sink.onTurnStart(input));
            replay.clear();
            publishAndRecord(sink -> sink.onTurnStart(input));
        }
    }

    @Override
    public void onMessage(SDKMessage msg) {
        synchronized (lock) {
            notifyPrimary(sink -> sink.onMessage(msg));
            publishAndRecord(sink -> sink.onMessage(msg));
        }
    }

    @Override
    public void onError(Throwable error, boolean userCancel) {
        synchronized (lock) {
            notifyPrimary(sink -> sink.onError(error, userCancel));
            publishAndRecord(sink -> sink.onError(error, userCancel));
        }
    }

    @Override
    public void onTurnComplete(TurnOutcome outcome) {
        synchronized (lock) {
            notifyPrimary(sink -> sink.onTurnComplete(outcome));
            publishAndRecord(sink -> sink.onTurnComplete(outcome));
        }
    }

    @Override
    public void onIdle() {
        synchronized (lock) {
            notifyPrimary(SessionSink::onIdle);
            publish(SessionSink::onIdle);
        }
    }

    private void publishAndRecord(Consumer<SessionSink> callback) {
        if (replay.size() >= MAX_REPLAY_EVENTS) {
            // Preserve index 0 (turn start) and discard the oldest body event.
            replay.remove(1);
        }
        replay.add(callback);
        publish(callback);
    }

    private void publish(Consumer<SessionSink> callback) {
        // Iterate a snapshot: an observer may unsubscribe itself from inside
        // its own callback (a turn-scoped gateway stream closes on turn
        // completion), which would otherwise mutate the live list mid-iterate.
        for (SessionSink observer : List.copyOf(observers)) {
            notifyObserver(observer, callback);
        }
    }

    /**
     * Runs one callback against {@link #primary}, isolating its failure the same way
     * {@link #notifyObserver} isolates an observer's: a broken render sink must not
     * abort the turn's message loop (it would silently detach every remaining message
     * this turn — for the primary *and* every observer, since {@link #publishAndRecord}
     * runs after this call) nor propagate out of the hub uncaught.
     */
    private void notifyPrimary(Consumer<SessionSink> callback) {
        try {
            callback.accept(primary);
        } catch (RuntimeException failure) {
            log.error("Session primary sink failed; turn continues, observers still notified",
                failure);
            report(failure);
        } catch (StackOverflowError | LinkageError | AssertionError failure) {
            // A native-image build reports a missing reflection or resource
            // registration as a LinkageError, which `catch (RuntimeException)` misses.
            // It is only reportable through the log: observerFailure takes a
            // RuntimeException and wrapping would misrepresent the cause's type.
            log.error("Session primary sink failed with a non-Exception error; turn continues",
                failure);
        }
    }

    private void notifyObserver(SessionSink observer, Consumer<SessionSink> callback) {
        try {
            callback.accept(observer);
        } catch (RuntimeException failure) {
            report(failure);
        } catch (StackOverflowError | LinkageError | AssertionError failure) {
            log.error("Session observer failed with a non-Exception error; delivery continues",
                failure);
        }
    }

    private void report(RuntimeException failure) {
        try {
            observerFailure.accept(failure);
        } catch (RuntimeException reportingFailure) {
            log.warn("Session sink failure reporter also failed", reportingFailure);
        }
    }

    private void unsubscribe(SessionSink observer) {
        synchronized (lock) {
            observers.remove(observer);
        }
    }
}
