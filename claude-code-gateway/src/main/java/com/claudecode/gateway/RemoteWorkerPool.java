package com.claudecode.gateway;

import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

/**
 * Keeps one {@link RemoteWorkerFeed} per running interactive process and reads the sessions each
 * of them hosts.
 *
 * <p>The directory is rescanned on every listing, so a process that exits drops out of the set and
 * one that starts joins it. A worker that fails to answer is detached and reconnected on the next
 * listing rather than retried in place.
 */
final class RemoteWorkerPool implements AutoCloseable {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(1);

    private record Snapshot(long takenAtNanos, List<SessionHostInfo> sessions) {}

    private final WebWorkerDirectory directory;
    private final MirrorHub hub;
    private final Map<String, RemoteWorkerFeed> feeds = new HashMap<>();
    private final Map<String, RemoteWorkerFeed> owners = new HashMap<>();
    private Snapshot snapshot;

    RemoteWorkerPool(WebWorkerDirectory directory, MirrorHub hub) {
        this.directory = directory;
        this.hub = hub;
    }

    synchronized List<SessionHostInfo> liveSessions() {
        long now = System.nanoTime();
        if (snapshot != null && now - snapshot.takenAtNanos() < SNAPSHOT_TTL.toNanos()) {
            return snapshot.sessions();
        }
        List<SessionHostInfo> sessions = scanSessions();
        snapshot = new Snapshot(now, sessions);
        return sessions;
    }

    /**
     * Submits a turn to the interactive process that hosts {@code sessionId}, or empty when no
     * live process lists it.
     */
    synchronized Optional<CompletableFuture<SessionLinkFrame>> submit(
            String sessionId, String prompt, String messageId) {
        liveSessions();
        RemoteWorkerFeed owner = owners.get(sessionId);
        return owner == null
            ? Optional.empty()
            : Optional.of(owner.submit(sessionId, prompt, messageId));
    }

    /**
     * Requests cancellation of a turn on the process that owns {@code sessionId}, or empty when no
     * live process lists it.
     */
    synchronized Optional<CompletableFuture<SessionLinkFrame>> cancelTurn(String sessionId) {
        liveSessions();
        RemoteWorkerFeed owner = owners.get(sessionId);
        return owner == null
            ? Optional.empty()
            : Optional.of(owner.cancelTurn(sessionId));
    }

    /**
     * Forwards an interaction answer to the process that owns {@code sessionId}, or empty when no
     * live process lists it.
     */
    synchronized Optional<CompletableFuture<SessionLinkFrame>> respondInteraction(
            String sessionId, ObjectNode payload) {
        liveSessions();
        RemoteWorkerFeed owner = owners.get(sessionId);
        return owner == null
            ? Optional.empty()
            : Optional.of(owner.respondInteraction(sessionId, payload));
    }

    private List<SessionHostInfo> scanSessions() {
        List<WebWorkerRegistration> workers = directory.scan();
        Set<String> sockets = workers.stream()
            .map(WebWorkerRegistration::socket)
            .collect(Collectors.toSet());
        detachMissing(sockets);
        owners.clear();
        List<SessionHostInfo> sessions = new ArrayList<>();
        for (WebWorkerRegistration worker : workers) {
            Optional<List<SessionHostInfo>> listed = listFrom(worker);
            listed.ifPresent(sessions::addAll);
        }
        return List.copyOf(sessions);
    }

    synchronized boolean owns(String sessionId) {
        liveSessions();
        return owners.containsKey(sessionId);
    }

    synchronized Optional<String> workDirOf(String sessionId) {
        return liveSessions().stream()
            .filter(session -> session.id().equals(sessionId))
            .map(SessionHostInfo::workDir)
            .filter(StringUtils::isNotEmpty)
            .findFirst();
    }

    private Optional<List<SessionHostInfo>> listFrom(WebWorkerRegistration worker) {
        RemoteWorkerFeed feed = feeds.get(worker.socket());
        if (feed == null) {
            feed = connect(worker);
            if (feed == null) return Optional.empty();
            feeds.put(worker.socket(), feed);
        }
        try {
            JsonNode response = feed.listSessions()
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).payload();
            List<SessionHostInfo> sessions = parseSessions(response);
            // A session already running when the link opens never sends session.activated,
            // so every listed session is attached here; repeats are no-ops in the feed.
            for (SessionHostInfo session : sessions) {
                feed.subscribe(session.id());
                owners.put(session.id(), feed);
            }
            return Optional.of(sessions);
        } catch (ExecutionException | TimeoutException _) {
            feeds.remove(worker.socket());
            feed.close();
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private RemoteWorkerFeed connect(WebWorkerRegistration worker) {
        RemoteWorkerFeed feed = new RemoteWorkerFeed(hub, Path.of(worker.socket()), worker.token());
        try {
            feed.start();
            return feed;
        } catch (IOException _) {
            feed.close();
            return null;
        }
    }

    private void detachMissing(Set<String> liveSockets) {
        Iterator<Map.Entry<String, RemoteWorkerFeed>> entries = feeds.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, RemoteWorkerFeed> entry = entries.next();
            if (liveSockets.contains(entry.getKey())) continue;
            entry.getValue().close();
            entries.remove();
            owners.values().removeIf(owner -> owner == entry.getValue());
        }
    }

    private static List<SessionHostInfo> parseSessions(JsonNode response) {
        List<SessionHostInfo> sessions = new ArrayList<>();
        for (JsonNode node : response.path("sessions")) {
            String id = node.path("id").asText("");
            if (id.isEmpty()) continue;
            sessions.add(new SessionHostInfo(
                id,
                node.path("work_dir").asText(""),
                node.path("summary").asText(""),
                node.path("message_count").asInt(0),
                instant(node.path("modified_at").asText("")),
                node.path("git_branch").asText("")));
        }
        return sessions;
    }

    private static Instant instant(String value) {
        if (value.isEmpty()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        feeds.values().forEach(RemoteWorkerFeed::close);
        feeds.clear();
        owners.clear();
        snapshot = null;
    }
}
