package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Session-scoped selection of one optional IM collaboration channel.
 */
@Explanation("Lets one terminal session opt into one configured IM channel")
public final class SessionCollaborationController {

    private static final Pattern CHANNEL = Pattern.compile("[a-z][a-z0-9_-]{0,31}");

    public enum Origin { LOCAL, REMOTE }

    public record Selection(String sessionId, String channel) {
        public Selection {
            sessionId = normalized(sessionId);
            channel = normalized(channel);
        }

        public boolean enabled() { return !channel.isEmpty(); }
        public String displayValue() {
            if (!enabled()) return "Off";
            return Character.toUpperCase(channel.charAt(0)) + channel.substring(1);
        }
    }

    public record Change(String sessionId, String channel, boolean enabled,
                         Origin origin, SessionHostInfo info) {}

    @FunctionalInterface
    public interface Listener { void onChanged(Change change); }

    private final SessionHostRegistry sessions;
    private final Map<String, String> selectedBySession = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<String> availableChannels = List.of();

    public SessionCollaborationController(SessionHostRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    public List<String> availableChannels() { return availableChannels; }

    public void replaceAvailableChannels(List<String> channels) {
        availableChannels = channels == null ? List.of() : channels.stream()
            .map(SessionCollaborationController::normalized)
            .filter(channel -> CHANNEL.matcher(channel).matches())
            .distinct().sorted().toList();
        selectedBySession.entrySet().removeIf(entry ->
            !availableChannels.contains(entry.getValue()));
    }

    public Selection current() {
        return sessions.current().map(session -> selection(session.info().id()))
            .orElseGet(() -> new Selection("", ""));
    }

    public Selection selection(String sessionId) {
        String id = normalized(sessionId);
        return new Selection(id, selectedBySession.getOrDefault(id, ""));
    }

    public void selectCurrent(String channel) {
        SessionHostSession session = sessions.current().orElseThrow(() ->
            new IllegalStateException("no active session"));
        select(session.info(), channel, Origin.LOCAL);
    }

    public void selectRemote(SessionHostInfo info, String channel) {
        select(info, channel, Origin.REMOTE);
    }

    public void disableCurrent() {
        SessionHostSession session = sessions.current().orElseThrow(() ->
            new IllegalStateException("no active session"));
        disable(session.info(), Origin.LOCAL);
    }

    public AutoCloseable subscribe(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.addIfAbsent(listener);
        return () -> listeners.remove(listener);
    }

    private void select(SessionHostInfo info, String rawChannel, Origin origin) {
        Objects.requireNonNull(info, "info");
        String channel = normalized(rawChannel);
        if (!CHANNEL.matcher(channel).matches() || !availableChannels.contains(channel)) {
            throw new IllegalArgumentException(
                "collaboration channel is not configured: " + channel);
        }
        String previous = selectedBySession.put(info.id(), channel);
        if (channel.equals(previous)) return;
        publish(new Change(info.id(), channel, true, origin, info));
    }

    private void disable(SessionHostInfo info, Origin origin) {
        if (selectedBySession.remove(info.id()) == null) return;
        publish(new Change(info.id(), "", false, origin, info));
    }

    private void publish(Change change) {
        for (Listener listener : listeners) {
            try { listener.onChanged(change); }
            catch (RuntimeException _) { /* optional endpoint failure */ }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
