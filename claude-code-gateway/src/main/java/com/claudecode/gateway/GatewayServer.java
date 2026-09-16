package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

/**
 * The gateway HTTP server: token-authenticated REST plus the SSE mirror
 * stream, all served in-process from the application instance.
 *
 * <p>Every route requires the launch token (see {@link GatewayAuthFilter}).
 * The SSE mirror runs one {@link SseConnection} lane per client on top of the
 * {@link MirrorHub} journal, so a stuck browser tab disconnects itself
 * instead of stalling the turn pipeline.
 *
 * <p>Protocol faces route per session: a request carrying
 * {@code metadata.session_id} submits to that headless session (opened through
 * {@code POST /api/sessions/open}, possibly in another project directory),
 * while requests without one keep submitting to the registry's active
 * (TUI) session. One shared in-flight guard bounds each session to one
 * concurrent turn without blocking other sessions.
 *
 * <p><b>Contract discipline</b>: this class's routing is the ground truth for
 * the gateway's web-facing wire contract. Any route, request/response shape,
 * or status-code change here must be mirrored in the root {@code openapi.yaml}
 * (paths, schemas, and tags) in the same change.
 */
public final class GatewayServer implements AutoCloseable {

    /** Immutable server configuration; the token never leaves the process. */
    public record Config(String host, int port, int laneCapacity, Duration keepAlive) {
        public Config {
            if (laneCapacity < 1) throw new IllegalArgumentException("laneCapacity must be positive");
        }

        public Config(String host, int port) {
            this(host, port, 4_096, Duration.ofSeconds(20));
        }
    }

    private static final int NOT_FOUND = 404;
    private static final int UNAUTHORIZED = 401;

    private final Config config;
    private final GatewayAuthFilter auth;
    private final SessionHostRegistry registry;
    private final MirrorHub mirror;
    private final GatewayHeadlessSessions headless;
    private final MessagesHandler messages;
    private final ChatHandler chat;
    private final ResponsesHandler responses;
    private final GatewaySessionsHandler sessionsApi;
    private final GatewaySessionCatalogPort catalog;
    private final GatewayPermissionsHandler permissionsApi;
    private final GatewayMessagesSnapshotHandler messagesSnapshotApi;
    private final GatewaySettingsHandler settingsApi;
    private final GatewayScheduleHandler scheduleApi;
    private final GatewayModelsHandler modelsApi;
    private final GatewayCommandsHandler commandsApi;
    private final GatewaySessionContextHandler sessionContextApi;
    private final ContextTimelineLedger contextTimeline;
    private final GatewayContextTimelineHandler contextTimelineApi;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile HttpServer server;

    public GatewayServer(Config config, String token, SessionHostRegistry registry) {
        this(config, token, registry, new GatewaySessionCatalogPort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog) {
        this(config, token, registry, catalog, new GatewayHeadlessSessions() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless) {
        this(config, token, registry, catalog, headless, null);
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions) {
        this(config, token, registry, catalog, headless, interactions,
            new GatewaySessionMessagesPort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages) {
        this(config, token, registry, catalog, headless, interactions, sessionMessages,
            new GatewaySettingsPort() {}, new GatewaySchedulePort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule) {
        this(config, token, registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, new GatewayModelsPort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models) {
        this(config, token, registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, new GatewayCommandsPort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands) {
        this(config, token, registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, new GatewaySessionContextPort() {});
    }

    public GatewayServer(Config config, String token, SessionHostRegistry registry,
            GatewaySessionCatalogPort catalog, GatewayHeadlessSessions headless,
            InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext) {
        this.config = config;
        this.auth = new GatewayAuthFilter(token);
        this.registry = registry;
        this.headless = headless;
        this.mirror = new MirrorHub(registry);
        // The context-timeline ledger follows the same sessions the mirror
        // does: it must observe every hub message so compaction's in-place
        // rewrite of the live rows is archived before they are gone.
        this.contextTimeline = new ContextTimelineLedger(registry, sessionContext);
        this.contextTimelineApi = new GatewayContextTimelineHandler(contextTimeline);
        // One in-flight guard shared by every protocol face: the busy error is
        // per session, and concurrent headless sessions must not block each
        // other through per-handler state.
        InFlightGuard inFlight = new InFlightGuard();
        // metadata.session_id routes by id across both session kinds: an open
        // headless session first, then the active TUI session (the id is
        // visible in /api/sessions, so a web client can address the TUI
        // conversation directly); absent id keeps the legacy behavior of
        // targeting whatever session is active.
        ProtocolTurnRunner.SessionResolver resolver = sessionId -> {
            if (sessionId == null) {
                return registry.currentActivation()
                    .map(SessionHostRegistry.ActivationResult::session);
            }
            Optional<SessionHostSession> headlessSession = headless.find(sessionId);
            if (headlessSession.isPresent()) return headlessSession;
            return registry.currentActivation()
                .filter(active -> Strings.CS.equals(active.session().info().id(),
                    sessionId))
                .map(SessionHostRegistry.ActivationResult::session);
        };
        this.messages = new MessagesHandler(resolver, inFlight);
        this.chat = new ChatHandler(resolver, inFlight);
        this.responses = new ResponsesHandler(resolver, inFlight);
        this.sessionsApi = new GatewaySessionsHandler(headless,
            new GatewaySessionsHandler.Lifecycle() {
                @Override public void onOpened(SessionHostSession session) {
                    mirror.attachSession(session);
                    contextTimeline.attach(session);
                }
                @Override public void onClosed(String sessionId) {
                    mirror.detachSession(sessionId);
                    contextTimeline.forget(sessionId);
                }
            });
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        // The shared interaction coordinator (when wired) makes the gateway a
        // remote observer of every pending permission ask: asks project into
        // the mirror stream and the respond endpoint answers them first-come.
        // The remote endpoint is a broadcast list, so the IM link's presenter
        // (if the Turn Collaboration endpoint is running) keeps receiving
        // asks beside this one.
        if (interactions != null) {
            GatewayInteractionPresenter presenter = new GatewayInteractionPresenter(mirror);
            interactions.register(InteractionFeatures.PERMISSION, presenter);
            interactions.register(InteractionFeatures.USER_QUESTION, presenter);
            this.permissionsApi = new GatewayPermissionsHandler(interactions);
        } else {
            this.permissionsApi = null;
        }
        this.messagesSnapshotApi = new GatewayMessagesSnapshotHandler(headless,
            sessionMessages);
        this.settingsApi = new GatewaySettingsHandler(settings);
        this.scheduleApi = new GatewayScheduleHandler(schedule);
        this.modelsApi = new GatewayModelsHandler(models);
        this.commandsApi = new GatewayCommandsHandler(commands);
        this.sessionContextApi = new GatewaySessionContextHandler(sessionContext);
        // The turn-completion delta folds read the same durable metrics the
        // session-context endpoint serves — one projection, two consumers.
        mirror.metricsReader(sessionContext::metrics);
        registry.subscribe(event -> {
            if (event.type() == SessionHostRegistry.EventType.ACTIVATED) {
                registry.currentActivation()
                    .map(SessionHostRegistry.ActivationResult::session)
                    .ifPresent(session -> {
                        mirror.attach(session);
                        contextTimeline.activated(session);
                        // The activation notice mirrors the IM link's
                        // session.activated frame: a reconnecting web client
                        // learns the switch point from the journal ring.
                        mirror.publishActivated(session.info(),
                            event.origin().name().toLowerCase(Locale.ROOT));
                    });
            }
        });
        mirror.attachCurrent();
        registry.currentActivation().map(SessionHostRegistry.ActivationResult::session)
            .ifPresent(contextTimeline::attach);
    }

    /** Binds the port and starts serving; returns once the socket is live. */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) return;
        InetAddress address = Strings.CS.equals("0.0.0.0", config.host())
            ? InetAddress.getByName("0.0.0.0")
            : InetAddress.getLoopbackAddress();
        HttpServer created = HttpServer.create(
            new InetSocketAddress(address, config.port()), 0);
        // One virtual thread per exchange: every SSE connection parks its own
        // lane thread while REST handlers finish quickly.
        created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        created.createContext("/", this::route);
        created.start();
        server = created;
    }

    /** The bound port — meaningful when {@code Config.port} was 0 (auto). */
    public int port() {
        HttpServer snapshot = server;
        if (snapshot == null) throw new IllegalStateException("gateway server is not started");
        return snapshot.getAddress().getPort();
    }

    /** The mirror fan-out hub, for interaction presenters to publish into. */
    public MirrorHub mirror() {
        return mirror;
    }

    @Override
    public void close() {
        HttpServer snapshot = server;
        server = null;
        if (snapshot != null) snapshot.stop(0);
        mirror.detach();
        contextTimeline.close();
        sessionsApi.closeAll();
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        boolean get = Strings.CS.equals("GET", method);
        boolean post = Strings.CS.equals("POST", method);
        boolean delete = Strings.CS.equals("DELETE", method);
        // Static webui serving takes the GET paths the API never claims: the
        // landing page and the bundle's /webui/** assets. Every API route
        // below matches before this fallback runs, so the static handler sees
        // only unmatched GETs.
        if (get && GatewayStaticFiles.serveIfStatic(exchange)) {
            return;
        }
        // Long-lived SSE exchanges and the async sessions listing keep their
        // exchange open past this handler's return; every other exchange is
        // closed here.
        if (get && Strings.CS.equals("/api/events", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            streamMirror(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/messages", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            messages.handle(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/chat/completions", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            chat.handle(exchange);
            return;
        }
        if (post && Strings.CS.equals("/v1/responses", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            responses.handle(exchange);
            return;
        }
        if (get && Strings.CS.equals("/api/sessions", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            // The fingerprint-validated catalog listing may block on transcript
            // reads; respond off the exchange thread and own the exchange there.
            listSessions(exchange);
            return;
        }
        if (post && (Strings.CS.equals("/api/sessions/open", path)
                || Strings.CS.equals("/api/sessions/close", path))) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            // The open path assembles an engine (slow I/O) off the exchange thread.
            String targetPath = path;
            Thread.ofVirtual().name("gateway-sessions-api").start(() -> {
                try (exchange) {
                    sessionsApi.handle(exchange,
                        Strings.CS.equals("/api/sessions/open", targetPath));
                } catch (IOException _) {
                    // The client disconnected mid-request; nothing to recover.
                } catch (RuntimeException failure) {
                    try {
                        respondJson(exchange, 500, errorBody("api_error",
                            "session request failed: " + failure.getMessage()));
                    } catch (IOException _) {
                        // Client already gone.
                    }
                }
            });
            return;
        }
        if (get && Strings.CS.startsWith(path, "/api/sessions/")
                && Strings.CS.endsWith(path, "/messages")) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            String sessionId = URLDecoder.decode(
                path.substring("/api/sessions/".length(),
                    path.length() - "/messages".length()), StandardCharsets.UTF_8);
            if (sessionId.isEmpty()) {
                try (exchange) {
                    respondJson(exchange, 400, errorBody("invalid_request",
                        "session id is required"));
                }
                return;
            }
            messagesSnapshotApi.handle(exchange, sessionId);
            return;
        }
        if (post && Strings.CS.equals("/api/permissions/respond", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            if (permissionsApi == null) {
                try (exchange) {
                    respondJson(exchange, NOT_FOUND, errorBody("not_found",
                        "permission interaction is not configured"));
                }
                return;
            }
            permissionsApi.handle(exchange);
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/settings", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            try (exchange) {
                if (get) {
                    settingsApi.handleGet(exchange);
                } else {
                    settingsApi.handlePost(exchange);
                }
            }
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/schedule", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            try (exchange) {
                if (get) {
                    scheduleApi.handleGet(exchange);
                } else {
                    scheduleApi.handlePost(exchange);
                }
            }
            return;
        }
        if (delete && Strings.CS.startsWith(path, "/api/schedule/")) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            String taskId = URLDecoder.decode(
                path.substring("/api/schedule/".length()), StandardCharsets.UTF_8);
            try (exchange) {
                scheduleApi.handleDelete(exchange, taskId);
            }
            return;
        }
        if (get && Strings.CS.equals("/api/commands", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            try (exchange) {
                commandsApi.handleGet(exchange);
            }
            return;
        }
        if (get && Strings.CS.startsWith(path, "/api/session/context/")) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            String face = path.substring("/api/session/context/".length());
            try (exchange) {
                switch (face) {
                    case "timeline" -> contextTimelineApi.handleTimeline(exchange);
                    case "detail" -> contextTimelineApi.handleDetail(exchange);
                    case "content" -> contextTimelineApi.handleContent(exchange);
                    case "overview" -> contextTimelineApi.handleOverview(exchange);
                    default -> respondJson(exchange, NOT_FOUND, errorBody("not_found",
                        "unknown context face: " + face));
                }
            }
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/session/context", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            try (exchange) {
                if (get) {
                    sessionContextApi.handleGet(exchange);
                } else {
                    sessionContextApi.handlePost(exchange);
                }
            }
            return;
        }
        if ((get || post) && Strings.CS.equals("/api/models", path)) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            try (exchange) {
                if (get) {
                    modelsApi.handleGet(exchange);
                } else {
                    modelsApi.handlePost(exchange);
                }
            }
            return;
        }
        if (delete && Strings.CS.startsWith(path, "/api/models/")) {
            if (!auth.authenticated(exchange)) {
                try (exchange) {
                    respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                        "Provide the launch token as Authorization: Bearer or ?token="));
                }
                return;
            }
            String modelName = URLDecoder.decode(
                path.substring("/api/models/".length()), StandardCharsets.UTF_8);
            try (exchange) {
                modelsApi.handleDelete(exchange, modelName);
            }
            return;
        }
        try (exchange) {
            if (!auth.authenticated(exchange)) {
                respondJson(exchange, UNAUTHORIZED, errorBody("authentication_required",
                    "Provide the launch token as Authorization: Bearer or ?token="));
                return;
            }
            respondJson(exchange, NOT_FOUND, errorBody("not_found", "Unknown route"));
        }
    }

    /** Sessions per project row by default: paged listing keeps one request's rows bounded. */
    private static final int DEFAULT_PER_PROJECT = 5;

    private void listSessions(HttpExchange exchange) throws IOException {
        drain(exchange);
        int perProjectLimit = perProjectLimitOf(exchange);
        // Off the exchange thread: the fingerprint-validated listing may block
        // on transcript reads. This handler owns the exchange lifecycle here —
        // the route dispatcher no longer closes it on return.
        Thread.ofVirtual().name("gateway-sessions-list").start(() -> {
            // A plain try/finally, not try-with-resources: a fallback error
            // response must still reach the wire from the catch block below,
            // and try-with-resources closes the exchange in its own finally
            // before that catch runs — closing the socket out from under the
            // 500 write and producing an empty reply instead of an error body.
            try {
                respondJson(exchange, 200,
                    sessionsBody(perProjectLimit).toString());
            } catch (IOException _) {
                // The client disconnected mid-listing; nothing to recover.
            } catch (RuntimeException failure) {
                try {
                    respondJson(exchange, 500, errorBody("api_error",
                        "session listing failed: " + failure.getMessage()));
                } catch (IOException _) {
                    // Client already gone.
                }
            } finally {
                exchange.close();
            }
        });
    }

    /**
     * Two-level project→session body when a catalog adapter is injected
     * (the shape of the TUI's {@code /resume} project picker); otherwise a
     * flat fallback over the registry's own listing. {@code perProjectLimit}
     * truncates each project's rows to its most recent sessions (the total
     * stays in {@code session_count}); {@code <= 0} serves every row.
     */
    private ObjectNode sessionsBody(int perProjectLimit) {
        ObjectNode body =
            JsonUtils.getMapper().createObjectNode();
        List<GatewaySessionCatalogPort.ProjectEntry> projects =
            catalog.listProjects(perProjectLimit);
        if (!projects.isEmpty()) {
            ArrayNode projectArray = body.putArray("projects");
            for (GatewaySessionCatalogPort.ProjectEntry project : projects) {
                ObjectNode projectNode = JsonUtils.getMapper().createObjectNode();
                projectNode.put("project_path", project.projectPath());
                projectNode.put("project_name", project.projectName());
                projectNode.put("session_count", project.sessionCount());
                ArrayNode sessionArray = projectNode.putArray("sessions");
                for (GatewaySessionCatalogPort.SessionEntry entry : project.sessions()) {
                    ObjectNode sessionNode = JsonUtils.getMapper().createObjectNode();
                    sessionNode.put("id", entry.id());
                    if (hasText(entry.summary())) sessionNode.put("summary", entry.summary());
                    sessionNode.put("message_count", entry.messageCount());
                    sessionNode.put("modified_at", Instant.ofEpochMilli(
                        entry.lastModifiedMs()).toString());
                    if (hasText(entry.gitBranch())) sessionNode.put("git_branch", entry.gitBranch());
                    if (hasText(entry.cwd())) sessionNode.put("cwd", entry.cwd());
                    if (hasText(entry.customTitle())) {
                        sessionNode.put("custom_title", entry.customTitle());
                    }
                    if (hasText(entry.firstPrompt())) {
                        sessionNode.put("first_prompt", entry.firstPrompt());
                    }
                    if (registry.isCurrent(entry.id())) sessionNode.put("active", true);
                    // A session row the client can route to right now via
                    // metadata.session_id without a further open round-trip.
                    if (headless.isOpen(entry.id())) sessionNode.put("headless_open", true);
                    sessionArray.add(sessionNode);
                }
                projectArray.add(projectNode);
            }
            return body;
        }
        // Fallback: flat listing over the registry when no catalog is wired.
        List<SessionHostInfo> sessions = new ArrayList<>(registry.list());
        sessions.sort(Comparator.comparing(
            info -> info.modifiedAt() == null ? null : info.modifiedAt(),
            Comparator.nullsFirst(Comparator.reverseOrder())));
        ArrayNode array = body.putArray("sessions");
        for (SessionHostInfo info : sessions) {
            ObjectNode node = JsonUtils.getMapper().createObjectNode();
            node.put("id", info.id());
            if (!info.workDir().isEmpty()) node.put("work_dir", info.workDir());
            if (!info.summary().isEmpty()) node.put("summary", info.summary());
            node.put("message_count", info.messageCount());
            if (info.modifiedAt() != null) node.put("modified_at", info.modifiedAt().toString());
            if (!info.gitBranch().isEmpty()) node.put("git_branch", info.gitBranch());
            if (registry.isCurrent(info.id())) node.put("active", true);
            if (headless.isOpen(info.id())) node.put("headless_open", true);
            array.add(node);
        }
        return body;
    }

    private void streamMirror(HttpExchange exchange) throws IOException {
        // The request body must be drained before the response starts, or the
        // connection cannot be reused (same keep-alive rule the smoke fake
        // server documents).
        drain(exchange);
        long lastEventId = parseLastEventId(exchange);
        // Optional session filter: frames from every followed session stream
        // by default; ?session_id= narrows the stream to one conversation view.
        String sessionFilter = queryParam(exchange, "session_id");
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream output = exchange.getResponseBody();
        MirrorSubscriptions subscriptions = new MirrorSubscriptions();
        SseConnection connection = SseConnection.start(
            output, config.laneCapacity(), config.keepAlive(), subscriptions::close);
        for (MirrorHub.MirrorFrame frame : mirror.replayAfter(lastEventId)) {
            if (sessionFilter == null
                    || Strings.CS.contains(frame.data(), "\"session_id\":\"" + sessionFilter + "\"")) {
                connection.offer(SseFrameWriter.event(
                    frame.event(), Long.toString(frame.id()), frame.data()));
            }
        }
        subscriptions.set(mirror.subscribe(frame -> {
            if (sessionFilter == null
                    || Strings.CS.contains(frame.data(), "\"session_id\":\"" + sessionFilter + "\"")) {
                connection.offer(SseFrameWriter.event(
                    frame.event(), Long.toString(frame.id()), frame.data()));
            }
        }));
    }

    /** One query parameter's decoded value, or null when absent. */
    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (!Strings.CS.equals(name, key)) continue;
            String raw = eq < 0 ? "" : pair.substring(eq + 1);
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * The {@code ?per_project=} page size: 1..1000 rows per project, the
     * default {@link #DEFAULT_PER_PROJECT} when absent or unparsable, and
     * unlimited ({@code 0}) only on the explicit {@code all}.
     */
    private static int perProjectLimitOf(HttpExchange exchange) {
        String raw = queryParam(exchange, "per_project");
        if (raw == null) return DEFAULT_PER_PROJECT;
        if (Strings.CS.equals("all", raw.strip())) return 0;
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed < 1) return 0;
            return Math.min(parsed, 1_000);
        } catch (NumberFormatException _) {
            return DEFAULT_PER_PROJECT;
        }
    }

    /** Releases the mirror subscription when the connection lane closes. */
    private static final class MirrorSubscriptions {
        private volatile AutoCloseable subscription;

        void set(AutoCloseable subscription) {
            this.subscription = subscription;
        }

        void close() {
            AutoCloseable current = subscription;
            subscription = null;
            if (current != null) {
                try { current.close(); } catch (Exception _) {}
            }
        }
    }

    private static long parseLastEventId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        if (StringUtils.isEmpty(header)) return 0;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** {@code SessionInfo}'s catalog-backed fields (summary, customTitle, ...) are nullable. */
    private static boolean hasText(String value) {
        return StringUtils.isNotEmpty(value);
    }

    private static String errorBody(String code, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", code);
        error.put("message", message);
        return body.toString();
    }

    private static void drain(HttpExchange exchange) throws IOException {
        try (var body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
    }
}
