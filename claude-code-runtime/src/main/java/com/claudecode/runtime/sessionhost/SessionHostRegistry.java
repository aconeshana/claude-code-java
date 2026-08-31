package com.claudecode.runtime.sessionhost;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Authoritative registry for the application instance's active semantic session.
 */
@Explanation("Coordinates one application session across terminal and IM endpoints")
public final class SessionHostRegistry {

    public enum Origin { LOCAL, REMOTE }
    public enum EventType { ACTIVATED, UPDATED, ENDED }

    public record LifecycleEvent(
            EventType type,
            Origin origin,
            SessionHostInfo info,
            String reason,
            String activationId,
            long activationGeneration) {
    }

    /** Causal result returned to a Session Link {@code session.open} caller. */
    public record ActivationResult(
            SessionHostSession session,
            String activationId,
            long activationGeneration,
            Origin origin) {}

    @FunctionalInterface
    public interface Listener {
        void onLifecycleEvent(LifecycleEvent event);
    }

    public interface Activator {
        CompletionStage<SessionHostSession> activate(SessionOpenRequest request);
        List<SessionHostInfo> list();
    }

    private final Activator activator;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> activeTurnSession = new AtomicReference<>();
    private final Object activationLock = new Object();
    private final Object eventSubscriptionLock = new Object();
    private long activationGeneration;
    private PendingActivation remoteActivation;
    private AutoCloseable eventSubscription;
    private volatile SessionHostSession current;
    private volatile ActivationResult currentActivation;

    private static final class PendingActivation {
        private final String requestedSessionId;
        private final String activationId;
        private final long generation;
        private volatile ActivationResult activatedByUi;

        private PendingActivation(
                String requestedSessionId, String activationId, long generation) {
            this.requestedSessionId = requestedSessionId;
            this.activationId = activationId;
            this.generation = generation;
        }

        private boolean matches(String sessionId) {
            return StringUtils.isBlank(requestedSessionId) || requestedSessionId.equals(sessionId);
        }
    }

    public SessionHostRegistry(Activator activator) {
        this.activator = Objects.requireNonNull(activator, "activator");
    }

    Optional<SessionHostSession> current() {
        return Optional.ofNullable(current);
    }

    /** Returns the current active identity and its monotonic generation. */
    public Optional<ActivationResult> currentActivation() {
        return Optional.ofNullable(currentActivation);
    }

    /** Returns a non-owning view only when {@code sessionId} is currently active. */
    public Optional<SessionHostSessionView> borrowCurrent(String sessionId) {
        return current()
            .filter(session -> session.info().id().equals(sessionId))
            .map(SessionHostSessionView::of);
    }

    /** Returns whether the supplied ID identifies the active application session. */
    public boolean isCurrent(String sessionId) {
        return current().map(session -> session.info().id().equals(sessionId)).orElse(false);
    }

    public List<SessionHostInfo> list() {
        return List.copyOf(activator.list());
    }

    public AutoCloseable subscribe(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
        return () -> listeners.remove(listener);
    }

    /** Called by the local UI after a new/resumed session becomes authoritative. */
    public void activateLocal(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        LifecycleEvent event;
        synchronized (activationLock) {
            PendingActivation pending = remoteActivation;
            if (pending != null
                    && pending.generation == activationGeneration
                    && pending.matches(session.info().id())) {
                ActivationResult result = activateLocked(
                    session, Origin.REMOTE, pending.activationId, pending.generation);
                pending.activatedByUi = result;
                event = activatedEvent(result, Origin.REMOTE);
            } else {
                long generation = ++activationGeneration;
                ActivationResult result = activateLocked(
                    session, Origin.LOCAL, "", generation);
                event = activatedEvent(result, Origin.LOCAL);
            }
        }
        publish(event);
    }

    /** Publishes refreshed metadata for the currently active session. */
    public void refreshLocal(SessionHostSession session) {
        Objects.requireNonNull(session, "session");
        LifecycleEvent event;
        synchronized (activationLock) {
            ActivationResult snapshot = currentActivation;
            if (snapshot == null || !snapshot.session().info().id().equals(session.info().id())) {
                return;
            }
            current = session;
            observeTurnLifecycle(session);
            currentActivation = new ActivationResult(
                session, snapshot.activationId(), snapshot.activationGeneration(), snapshot.origin());
            event = new LifecycleEvent(
                EventType.UPDATED, Origin.LOCAL, session.info(), "",
                snapshot.activationId(), snapshot.activationGeneration());
        }
        publish(event);
    }

    /** Called by the local UI while its normal shutdown path is still operational. */
    public void endLocal(String reason) {
        long generation;
        SessionHostSession snapshot;
        synchronized (activationLock) {
            generation = ++activationGeneration;
            snapshot = current;
            current = null;
            currentActivation = null;
        }
        activeTurnSession.set(null);
        synchronized (eventSubscriptionLock) {
            closeQuietly(eventSubscription);
            eventSubscription = null;
        }
        if (snapshot == null) return;
        publish(new LifecycleEvent(
            EventType.ENDED, Origin.LOCAL, snapshot.info(), Objects.requireNonNullElse(reason, ""),
            "", generation));
    }

    /** Executes {@code session.open}; an empty ID means create a fresh session. */
    public CompletionStage<SessionHostSession> open(SessionOpenRequest request) {
        return openActivation(request).thenApply(ActivationResult::session);
    }

    /** Executes {@code session.open} and returns its causal activation metadata. */
    public CompletionStage<ActivationResult> openActivation(SessionOpenRequest request) {
        Objects.requireNonNull(request, "request");
        PendingActivation pending;
        synchronized (activationLock) {
            ActivationResult snapshot = currentActivation;
            if (!StringUtils.isBlank(request.requestedSessionId()) && snapshot != null
                    && request.requestedSessionId().equals(snapshot.session().info().id())) {
                return CompletableFuture.completedFuture(new ActivationResult(
                    snapshot.session(), request.activationId(), snapshot.activationGeneration(),
                    snapshot.origin()));
            }
            String activeTurn = activeTurnSession.get();
            if (activeTurn != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "session " + activeTurn + " has an active turn; retry after it becomes idle"));
            }
            if (remoteActivation != null) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("another session activation is already in progress"));
            }
            pending = new PendingActivation(
                request.requestedSessionId(), request.activationId(), ++activationGeneration);
            remoteActivation = pending;
        }
        try {
            return activator.activate(request).thenApply(session -> {
                LifecycleEvent event;
                ActivationResult result;
                synchronized (activationLock) {
                    ActivationResult activatedByUi = pending.activatedByUi;
                    if (activatedByUi != null
                            && currentActivation == activatedByUi
                            && activatedByUi.session().info().id().equals(session.info().id())) {
                        return activatedByUi;
                    }
                    if (activationGeneration != pending.generation) {
                        throw new IllegalStateException(
                            "session activation was superseded by a newer local lifecycle change");
                    }
                    result = activateLocked(
                        session, Origin.REMOTE, pending.activationId, pending.generation);
                    event = activatedEvent(result, Origin.REMOTE);
                }
                publish(event);
                return result;
            }).whenComplete((_, _) -> {
                synchronized (activationLock) {
                    if (remoteActivation == pending) remoteActivation = null;
                }
            });
        } catch (RuntimeException failure) {
            synchronized (activationLock) {
                if (remoteActivation == pending) remoteActivation = null;
            }
            throw failure;
        }
    }

    private ActivationResult activateLocked(
            SessionHostSession session, Origin origin, String activationId, long generation) {
        current = Objects.requireNonNull(session, "session");
        observeTurnLifecycle(session);
        ActivationResult result = new ActivationResult(
            session, Objects.requireNonNullElse(activationId, ""), generation, origin);
        currentActivation = result;
        return result;
    }

    private static LifecycleEvent activatedEvent(ActivationResult result, Origin origin) {
        return new LifecycleEvent(
            EventType.ACTIVATED, origin, result.session().info(), "",
            result.activationId(), result.activationGeneration());
    }

    private void observeTurnLifecycle(SessionHostSession session) {
        synchronized (eventSubscriptionLock) {
            closeQuietly(eventSubscription);
            String sessionId = session.info().id();
            eventSubscription = session.events().subscribe(new SessionSink() {
                @Override public void onTurnStart(UserInput input) {
                    activeTurnSession.set(sessionId);
                }
                @Override public void onMessage(SDKMessage msg) {}
                @Override public void onError(Throwable error, boolean userCancel) {
                    activeTurnSession.compareAndSet(sessionId, null);
                }
                @Override public void onTurnComplete(TurnOutcome outcome) {
                    activeTurnSession.compareAndSet(sessionId, null);
                }
                @Override public void onIdle() {
                    activeTurnSession.compareAndSet(sessionId, null);
                }
            });
        }
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception _) {
            // Lifecycle fencing remains authoritative if an optional observer
            // cannot detach cleanly.
        }
    }

    private void publish(LifecycleEvent event) {
        for (Listener listener : listeners) {
            try {
                listener.onLifecycleEvent(event);
            } catch (RuntimeException _) {
                // Session lifecycle is authoritative even if an optional remote
                // endpoint cannot consume its lifecycle notification.
            }
        }
    }
}
