package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Mutable, shared-by-reference holder for a single session's id.
 */
public final class SessionIdentity {

    private volatile String sessionId;
    private final CopyOnWriteArrayList<Consumer<String>> changeListeners =
        new CopyOnWriteArrayList<>();

    private SessionIdentity(String sessionId) {
        this.sessionId = sessionId;
    }

    public static SessionIdentity newRandom() {
        return new SessionIdentity(UUID.randomUUID().toString());
    }

    public static SessionIdentity of(String existingId) {
        if (StringUtils.isBlank(existingId)) {
            throw new IllegalArgumentException("existingId must not be blank");
        }
        return new SessionIdentity(existingId);
    }

    public String get() {
        return sessionId;
    }

    public void set(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (Objects.equals(sessionId, id)) return;
        sessionId = id;
        notifyChanged(id);
    }

    public String regenerate() {
        String regenerated = UUID.randomUUID().toString();
        set(regenerated);
        return regenerated;
    }

    /** Observes future session switches. The current id is not replayed on subscription. */
    public AutoCloseable subscribeChanges(Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    private void notifyChanged(String id) {
        for (Consumer<String> listener : changeListeners) {
            try {
                listener.accept(id);
            } catch (RuntimeException _) {
                // One observer must not prevent the remaining session-scoped consumers.
            }
        }
    }
}
