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
        Objects.requireNonNull(observer, "observer");
        if (observer == primary) {
            throw new IllegalArgumentException("primary sink cannot subscribe to itself");
        }
        synchronized (lock) {
            if (observers.contains(observer)) return () -> unsubscribe(observer);
            for (Consumer<SessionSink> callback : replay) notifyObserver(observer, callback);
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
            primary.onTurnStart(input);
            replay.clear();
            publishAndRecord(sink -> sink.onTurnStart(input));
        }
    }

    @Override
    public void onMessage(SDKMessage msg) {
        synchronized (lock) {
            primary.onMessage(msg);
            publishAndRecord(sink -> sink.onMessage(msg));
        }
    }

    @Override
    public void onError(Throwable error, boolean userCancel) {
        synchronized (lock) {
            primary.onError(error, userCancel);
            publishAndRecord(sink -> sink.onError(error, userCancel));
        }
    }

    @Override
    public void onTurnComplete(TurnOutcome outcome) {
        synchronized (lock) {
            primary.onTurnComplete(outcome);
            publishAndRecord(sink -> sink.onTurnComplete(outcome));
        }
    }

    @Override
    public void onIdle() {
        synchronized (lock) {
            primary.onIdle();
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
        for (SessionSink observer : observers) {
            notifyObserver(observer, callback);
        }
    }

    private void notifyObserver(SessionSink observer, Consumer<SessionSink> callback) {
        try {
            callback.accept(observer);
        } catch (RuntimeException failure) {
            try {
                observerFailure.accept(failure);
            } catch (RuntimeException reportingFailure) {
                log.warn("Session observer failure reporter also failed", reportingFailure);
            }
        }
    }

    private void unsubscribe(SessionSink observer) {
        synchronized (lock) {
            observers.remove(observer);
        }
    }
}
