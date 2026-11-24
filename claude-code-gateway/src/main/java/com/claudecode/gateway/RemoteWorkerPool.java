package com.claudecode.gateway;

import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.runtime.sessionhost.SessionHostPermissionMode;
import com.claudecode.runtime.sessionhost.SessionHostPermissionState;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.claudecode.runtime.sessionlink.SessionLinkEndpoint;
import com.claudecode.runtime.sessionlink.SessionLinkFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps one {@link RemoteWorkerFeed} per running interactive process and reads the sessions each
 * of them hosts.
 *
 * <p>The directory is rescanned on every listing, so a process that exits drops out of the set and
 * one that starts joins it. A worker that fails to answer is detached and reconnected on the next
 * listing rather than retried in place.
 *
 * <p>A listing is also what attaches a worker's sessions, so a pool that only listed on request
 * would leave a freshly started process unfollowed until some client happened to ask: its live
 * frames went nowhere and its row stayed missing until the webui's next catalog poll. A serving
 * gateway therefore {@linkplain #startWatching watches} the directory and announces every change
 * to the set of live sessions on the mirror.
 */
final class RemoteWorkerPool implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RemoteWorkerPool.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(1);

    /** How often a serving gateway looks for interactive processes that started or exited. */
    static final Duration WATCH_INTERVAL = SNAPSHOT_TTL;

    private record Snapshot(long takenAtNanos, List<SessionHostInfo> sessions) {}

    private final WebWorkerDirectory directory;
    private final MirrorHub hub;
    private final Map<String, RemoteWorkerFeed> feeds = new HashMap<>();
    private final Map<String, RemoteWorkerFeed> owners = new HashMap<>();
    private final Map<Long, WebWorkerRegistration> workersByPid = new HashMap<>();
    private final Map<Long, List<SessionHostInfo>> sessionsByPid = new HashMap<>();
    private Snapshot snapshot;
    private Thread watcher;
    private boolean closed;

    RemoteWorkerPool(WebWorkerDirectory directory, MirrorHub hub) {
        this.directory = directory;
        this.hub = hub;
    }

    /**
     * Rescans the directory every {@code interval} until {@link #close}, so a process that starts
     * is followed within one interval, and a process that starts or exits is announced through
     * {@link MirrorHub#publishSessionsChanged} instead of waiting for a client's next poll.
     * Idempotent.
     */
    synchronized void startWatching(Duration interval) {
        if (watcher != null || closed) return;
        watcher = Thread.ofVirtual().name("remote-worker-watch").start(() -> watch(interval));
    }

    private void watch(Duration interval) {
        Set<String> announced = null;
        while (!Thread.currentThread().isInterrupted()) {
            Set<String> live = announced;
            try {
                live = liveSessions().stream()
                    .map(SessionHostInfo::id)
                    .collect(Collectors.toUnmodifiableSet());
            } catch (RuntimeException failure) {
                log.debug("[remote-pool] directory scan failed", failure);
            }
            // The first scan announces nothing: a client that connects reads the listing itself.
            // The notice goes out here, off the pool's lock, so a slow browser lane never holds up
            // a forwarded turn.
            if (announced != null && live != null && !live.equals(announced)) {
                hub.publishSessionsChanged();
            }
            announced = live;
            try {
                Thread.sleep(interval);
            } catch (InterruptedException _) {
                return;
            }
        }
    }

    synchronized List<SessionHostInfo> liveSessions() {
        // A watcher that was waiting for this lock while the pool closed must not dial the
        // workers again behind close's back.
        if (closed) return List.of();
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
            String sessionId, SessionHostSubmission submission) {
        liveSessions();
        RemoteWorkerFeed owner = owners.get(sessionId);
        return owner == null
            ? Optional.empty()
            : Optional.of(owner.submit(sessionId, submission));
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
        workersByPid.clear();
        sessionsByPid.clear();
        List<SessionHostInfo> sessions = new ArrayList<>();
        for (WebWorkerRegistration worker : workers) {
            workersByPid.put(worker.pid(), worker);
            Optional<List<SessionHostInfo>> listed = listFrom(worker);
            listed.ifPresent(found -> {
                sessions.addAll(found);
                sessionsByPid.put(worker.pid(), List.copyOf(found));
            });
        }
        return List.copyOf(sessions);
    }

    /** Returns every live TUI worker together with its active session metadata, if any. */
    synchronized List<GatewayCcConnectPort.LiveWorker> liveWorkers() {
        if (closed) return List.of();
        liveSessions();
        List<GatewayCcConnectPort.LiveWorker> result = new ArrayList<>(workersByPid.size());
        for (WebWorkerRegistration worker : workersByPid.values()) {
            List<SessionHostInfo> workerSessions = sessionsByPid.getOrDefault(worker.pid(), List.of());
            SessionHostInfo active = workerSessions.isEmpty() ? null : workerSessions.getFirst();
            result.add(new GatewayCcConnectPort.LiveWorker(
                worker.pid(),
                worker.socket(),
                worker.token(),
                worker.workDir(),
                worker.version(),
                active == null ? "" : active.id(),
                active == null ? "" : active.summary()));
        }
        result.sort((a, b) -> Long.compare(a.pid(), b.pid()));
        return List.copyOf(result);
    }

    Optional<GatewayCcConnectPort.CollaborationState> getCollaboration(long pid, String sessionId) {
        RemoteWorkerFeed feed = feedFor(pid, sessionId);
        if (feed == null) return Optional.empty();
        try {
            JsonNode payload = feed.getCollaboration(StringUtils.isBlank(sessionId) ? null : sessionId)
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).payload();
            return Optional.of(parseCollaborationState(payload));
        } catch (ExecutionException | TimeoutException _) {
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    Optional<GatewayCcConnectPort.CollaborationState> setCollaboration(
            long pid, String sessionId, String channel, boolean enabled) {
        RemoteWorkerFeed feed = feedFor(pid, sessionId);
        if (feed == null) return Optional.empty();
        JsonNode payload = requestState(
            feed.setCollaboration(StringUtils.isBlank(sessionId) ? null : sessionId, channel, enabled),
            "the session's host did not answer the collaboration change in time");
        return Optional.of(parseCollaborationState(payload));
    }

    private synchronized RemoteWorkerFeed feedFor(long pid, String sessionId) {
        if (closed) return null;
        liveSessions();
        if (pid > 0) {
            WebWorkerRegistration worker = workersByPid.get(pid);
            if (worker != null) {
                RemoteWorkerFeed byPid = feeds.get(worker.socket());
                if (byPid != null && byPid.isOpen()) return byPid;
            }
        }
        if (StringUtils.isNotBlank(sessionId)) {
            return owners.get(sessionId);
        }
        return null;
    }

    private static GatewayCcConnectPort.CollaborationState parseCollaborationState(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return new GatewayCcConnectPort.CollaborationState("", "", false, List.of());
        }
        List<String> channels = new ArrayList<>();
        for (JsonNode item : payload.path("available_channels")) {
            if (item.isTextual()) channels.add(item.asText());
        }
        return new GatewayCcConnectPort.CollaborationState(
            payload.path("session_id").asText(""),
            payload.path("channel").asText(""),
            payload.path("enabled").asBoolean(false),
            channels);
    }

    synchronized boolean owns(String sessionId) {
        liveSessions();
        return owners.containsKey(sessionId);
    }

    /**
     * Forwards one permission-mode read to the worker that hosts {@code sessionId},
     * answering the state exactly as the worker projected it.
     *
     * <p>Empty means the session is not remote-owned here. A worker that rejects
     * the request (a mode the gate does not know) completes exceptionally, so the
     * caller can surface its message instead of an invented default.
     */
    Optional<SessionHostPermissionState> permissionMode(String sessionId) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        try {
            return Optional.of(parsePermissionState(feed.getPermissionMode(sessionId)
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).payload()));
        } catch (ExecutionException | TimeoutException _) {
            return Optional.empty();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Forwards one permission-mode selection to the owning worker; same contract as above. */
    Optional<SessionHostPermissionState> selectPermissionMode(String sessionId, String mode) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        try {
            return Optional.of(parsePermissionState(feed.setPermissionMode(sessionId, mode)
                .get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).payload()));
        } catch (ExecutionException exception) {
            throw new IllegalStateException(linkFailureMessage(exception), exception);
        } catch (TimeoutException _) {
            throw new IllegalStateException("the session's host did not answer in time");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the session's host");
        }
    }

    /**
     * Forwards one model read to the worker that hosts {@code sessionId}.
     *
     * <p>Empty means the session is not remote-owned here, or its host did not
     * answer; a rejection the worker raises propagates as an
     * {@link IllegalStateException} carrying its message.
     */
    Optional<SessionHostModelState> model(String sessionId) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        return Optional.of(parseModelState(requestState(feed.getModel(sessionId),
            "the session's host did not answer the model request in time")));
    }

    /** Forwards one model selection to the owning worker; same contract as above. */
    Optional<SessionHostModelState> selectModel(String sessionId, String model) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        return Optional.of(parseModelState(requestState(feed.setModel(sessionId, model),
            "the session's host did not answer the model change in time")));
    }

    /** Forwards one effort read to the worker that hosts {@code sessionId}. */
    Optional<SessionHostEffortState> effort(String sessionId) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        return Optional.of(parseEffortState(requestState(feed.getEffort(sessionId),
            "the session's host did not answer the effort request in time")));
    }

    /**
     * A remote session's model selection and effort level as one
     * {@link GatewaySessionContextPort.ModelSelection}. Empty when this
     * gateway does not own the session. The model answer carries the
     * catalogue and the effort answer the levels, so both round trips are
     * needed for a complete seat.
     */
    Optional<GatewaySessionContextPort.ModelSelection> modelSelection(String sessionId) {
        Optional<SessionHostModelState> models = model(sessionId);
        if (models.isEmpty()) return Optional.empty();
        SessionHostModelState state = models.get();
        Optional<SessionHostEffortState> efforts = effort(sessionId);
        return Optional.of(new GatewaySessionContextPort.ModelSelection(
            state.current(),
            state.models().stream()
                .map(option -> new GatewaySessionContextPort.ModelChoice(option.name(),
                    option.label(), option.description(), option.defaultOption()))
                .toList(),
            efforts.map(SessionHostEffortState::current).orElse(""),
            efforts.map(SessionHostEffortState::effective).orElse(""),
            efforts.map(SessionHostEffortState::efforts).orElse(List.of())));
    }

    /** Forwards one effort selection to the owning worker; same contract as above. */
    Optional<SessionHostEffortState> selectEffort(String sessionId, String effort) {
        RemoteWorkerFeed feed = ownerOf(sessionId);
        if (feed == null) return Optional.empty();
        return Optional.of(parseEffortState(requestState(feed.setEffort(sessionId, effort),
            "the session's host did not answer the effort change in time")));
    }

    /**
     * One blocking round trip to the owning worker. A host-side rejection
     * (an unknown model, an unsupported effort level) is the worker's answer
     * about the request, so it surfaces as a rejection; an interrupted caller
     * keeps its interrupt flag and reports the same shape.
     */
    private static JsonNode requestState(CompletableFuture<SessionLinkFrame> pending,
            String timeoutMessage) {
        try {
            return pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).payload();
        } catch (ExecutionException failure) {
            throw new IllegalStateException(linkFailureMessage(failure), failure);
        } catch (TimeoutException _) {
            throw new IllegalStateException(timeoutMessage);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the session's host");
        }
    }

    private static SessionHostModelState parseModelState(JsonNode payload) {
        if (payload == null || payload.isNull()) return null;
        List<SessionHostModelOption> models = new ArrayList<>();
        for (JsonNode row : payload.path("models")) {
            models.add(new SessionHostModelOption(
                row.path("name").asText(""), row.path("label").asText(""),
                row.path("description").asText(""), row.path("alias").asText(""),
                row.path("default").asBoolean(false)));
        }
        return new SessionHostModelState(payload.path("current").asText(""), models);
    }

    private static SessionHostEffortState parseEffortState(JsonNode payload) {
        if (payload == null || payload.isNull()) return null;
        List<String> efforts = new ArrayList<>();
        for (JsonNode row : payload.path("efforts")) {
            if (row.isTextual()) efforts.add(row.asText());
        }
        return new SessionHostEffortState(
            payload.path("current").asText("auto"),
            payload.path("effective").asText(""), efforts);
    }

    /**
     * The worker currently claiming {@code sessionId}, or null. Polls the same
     * snapshot {@link #owns} uses so a freshly activated session routes here too.
     */
    private RemoteWorkerFeed ownerOf(String sessionId) {
        if (sessionId == null) return null;
        if (!owns(sessionId)) return null;
        return owners.get(sessionId);
    }

    private static SessionHostPermissionState parsePermissionState(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }
        List<SessionHostPermissionMode> modes = new ArrayList<>();
        JsonNode rawModes = payload.get("modes");
        if (rawModes != null && rawModes.isArray()) {
            for (JsonNode row : rawModes) {
                modes.add(new SessionHostPermissionMode(
                    row.path("value").asText(""), row.path("title").asText(""),
                    row.path("short_title").asText(""), row.path("symbol").asText(""),
                    row.path("color_key").asText(""),
                    row.path("available").asBoolean(false)));
            }
        }
        return new SessionHostPermissionState(
            payload.path("current").asText(""), modes,
            payload.path("bypass_permissions_available").asBoolean(false),
            payload.path("bypass_permissions_disabled_by_policy").asBoolean(false));
    }

    /**
     * The worker's rejection message, unwrapped from the completion wrapper. A
     * link error that carries no message still needs to read as a rejection
     * rather than as an empty reason.
     */
    private static String linkFailureMessage(ExecutionException failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        return StringUtils.isBlank(message)
            ? "the session's host rejected the permission mode"
            : message;
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
        if (feed != null && !feed.isOpen()) {
            // The host ended this link (a restart, a re-attach); nothing will
            // ever answer on it again, so drop it and dial a fresh one rather
            // than pinning a dead connection for the life of this process.
            RemoteWorkerFeed dead = feed;
            dead.close();
            feeds.remove(worker.socket());
            owners.entrySet().removeIf(entry -> entry.getValue() == dead);
            feed = null;
        }
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
            // A worker lists the session it is on (and only that one), so a listing is its
            // whole ownership claim: ids it no longer lists must stop routing here even though
            // this connection still holds their attachment (the TUI switched away).
            Set<String> listed = sessions.stream()
                .map(SessionHostInfo::id)
                .collect(Collectors.toSet());
            RemoteWorkerFeed owner = feed;
            owners.entrySet()
                .removeIf(entry -> entry.getValue() == owner && !listed.contains(entry.getKey()));
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
        SessionLinkEndpoint endpoint;
        try {
            endpoint = SessionLinkEndpoint.parse(worker.socket());
        } catch (IllegalArgumentException _) {
            return null;
        }
        RemoteWorkerFeed feed = new RemoteWorkerFeed(hub, endpoint, worker.token());
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
        closed = true;
        if (watcher != null) watcher.interrupt();
        feeds.values().forEach(RemoteWorkerFeed::close);
        feeds.clear();
        owners.clear();
        workersByPid.clear();
        sessionsByPid.clear();
        snapshot = null;
    }
}
