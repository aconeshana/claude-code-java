package com.claudecode.gateway;


import com.claudecode.core.message.Message;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionHostSubmission;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Predicate;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


    private static final Logger log = LoggerFactory.getLogger(GatewayServer.class);

    private final Config config;
    private final GatewayAuthFilter auth;
    private final MirrorHub mirror;
    private final MessagesHandler messages;
    private final ChatHandler chat;
    private final ResponsesHandler responses;
    private final GatewaySessionsHandler sessionsApi;
    private final GatewaySessionActionsHandler sessionActionsApi;
    private final GatewayRewindHandler rewindApi;
    private final GatewaySessionCatalogPort catalog;
    private final GatewayPermissionsHandler permissionsApi;
    private final GatewaySudoRespondHandler sudoApi;
    private final GatewayMessagesSnapshotHandler messagesSnapshotApi;
    private final GatewayTrajectoryHandler trajectoryApi;
    private final GatewaySettingsHandler settingsApi;
    private final GatewayScheduleHandler scheduleApi;
    private final GatewayRuntimeControlHandler runtimeControlApi;
    private final GatewayModelsHandler modelsApi;
    private final GatewayCommandsHandler commandsApi;
    private final GatewaySpinnerVerbsHandler spinnerVerbsApi;
    private final GatewayFileSuggestionsHandler fileSuggestionsApi;
    private final GatewayDirectoryPickerHandler directoryPickerApi = new GatewayDirectoryPickerHandler();
    private final GatewayProjectsHandler projectsApi;
    private final GatewaySessionContextHandler sessionContextApi;
    private final GatewayCapabilitiesHandler capabilitiesApi;
    private final GatewayPermissionModeHandler permissionModeApi;
    private final GatewaySessionCancelHandler sessionCancelApi;
    private final GatewayTaskBoardHandler taskBoardApi;
    private final GatewayChangesHandler changesApi;
    private final GatewayWorkspaceFileHandler workspaceFileApi;
    private final GatewayBackgroundTasksHandler backgroundTasksApi;
    private final LanGatewayConfigStore lanConfigStore;
    private final LanModelProxyHandler lanModelProxy;
    private final GatewayLanAdminHandler lanAdminApi;
    private final GatewayCcConnectHandler ccConnectApi;
    private final GatewayPluginsHandler pluginsApi;
    private final MacAwakeGuard awakeGuard = new MacAwakeGuard();
    private final ContextTimelineLedger contextTimeline;
    private final GatewayContextTimelineHandler contextTimelineApi;
    /** The registry activation feed; released by {@link #close()}. */
    private final AutoCloseable registrySubscription;
    private final RemoteWorkerPool remoteWorkers;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object bindLock = new Object();
    private volatile String currentBoundHost;
    private volatile HttpServer server;
    private final String localMachineHostName;
    private final GatewayRouter router;

    /**
     * Assembles a server from its required facts plus the optional capability
     * ports, each defaulting to an inert implementation.
     *
     * <p>The wiring used to be a fifteen-rung telescoping constructor chain
     * whose full arity reached nineteen positional arguments. That shape made
     * every added port a change to every rung above it and to every call site
     * below it — twenty-eight call sites spread over thirteen distinct
     * argument counts, each one an opportunity to hand a port to the wrong
     * slot, which is exactly how two interop tests broke when cancellation was
     * added. Here a port is named, added once, and existing call sites are
     * untouched.
     *
     * <p>{@code config}, {@code token} and {@code registry} have no defaults:
     * a server without them has nothing to serve and no one to serve.
     */
    public static Builder builder(Config config, String token, SessionHostRegistry registry) {
        return new Builder(config, token, registry);
    }

    /** Named-port builder for {@link GatewayServer}; see {@link #builder}. */
    public static final class Builder {
        private final Config config;
        private final String token;
        private final SessionHostRegistry registry;
        private GatewaySessionCatalogPort catalog = new GatewaySessionCatalogPort() {};
        private GatewayHeadlessSessions headless = new GatewayHeadlessSessions() {};
        private InteractionCoordinator interactions;
        private GatewaySessionMessagesPort sessionMessages = new GatewaySessionMessagesPort() {};
        private GatewaySettingsPort settings = new GatewaySettingsPort() {};
        private GatewayProjectsPort projects = new GatewayProjectsPort() {};
        private GatewaySchedulePort schedule = new GatewaySchedulePort() {};
        private GatewayRuntimeControlPort runtimeControl = new GatewayRuntimeControlPort() {};
        private GatewayModelsPort models = new GatewayModelsPort() {};
        private GatewayCommandsPort commands = new GatewayCommandsPort() {};
        private GatewaySessionContextPort sessionContext = new GatewaySessionContextPort() {};
        private SessionCapabilityPort capabilities;
        private GatewaySessionActionsPort actions = new GatewaySessionActionsPort() {};
        private GatewayPermissionModePort permissionMode = new GatewayPermissionModePort() {};
        private GatewaySpinnerVerbsPort spinnerVerbs = new GatewaySpinnerVerbsPort() {};
        private GatewayFileSuggestionsPort fileSuggestions = new GatewayFileSuggestionsPort() {};
        private GatewaySessionCancelPort cancel = _ -> false;
        private GatewayTaskBoardPort taskBoard = _ -> Optional.empty();
        private GatewayFileDiffPort fileDiff = GatewayFileDiffPort.none();
        private GatewayWorkspaceFilePort workspaceFile = GatewayWorkspaceFilePort.none();
        private GatewayBackgroundTasksPort backgroundTasks = new GatewayBackgroundTasksPort() {};
        private LanGatewayConfigStore lanConfigStore = LanGatewayConfigStore.inMemory();
        private GatewayLanProxyPort lanProxy = new GatewayLanProxyPort() {};
        private GatewayCcConnectPort ccConnect = new GatewayCcConnectPort() {};
        private GatewayPluginsPort plugins = new GatewayPluginsPort() {};
        private WebWorkerDirectory webWorkers;
        private boolean remoteSessionState;
        private RemoteSessionContextPort.MetricsFold remoteMetrics =
            (_, _) -> Optional.empty();

        private Builder(Config config, String token, SessionHostRegistry registry) {
            this.config = Objects.requireNonNull(config, "config");
            this.token = Objects.requireNonNull(token, "token");
            this.registry = Objects.requireNonNull(registry, "registry");
        }

        /** Enables the sessions of every interactive process registered in {@code value}. */
        public Builder webWorkers(WebWorkerDirectory value) {
            this.webWorkers = Objects.requireNonNull(value, "webWorkers");
            return this;
        }

        public Builder catalog(GatewaySessionCatalogPort value) {
            this.catalog = Objects.requireNonNull(value, "catalog");
            return this;
        }

        public Builder headless(GatewayHeadlessSessions value) {
            this.headless = Objects.requireNonNull(value, "headless");
            return this;
        }

        /** Absent is a supported state: the permission routes are then not mounted. */
        public Builder interactions(InteractionCoordinator value) {
            this.interactions = value;
            return this;
        }

        public Builder sessionMessages(GatewaySessionMessagesPort value) {
            this.sessionMessages = Objects.requireNonNull(value, "sessionMessages");
            return this;
        }

        /**
         * Enables forwarding of state that lives only in a session's hosting
         * process — its model selection and reasoning effort — over that
         * process's Session Link. Without it a remote session's composer seat
         * is blank and a selection is rejected as unsupported.
         */
        public Builder remoteSessionState(boolean value) {
            this.remoteSessionState = value;
            return this;
        }

        /**
         * The durable HUD fold of a session hosted elsewhere, replayed from
         * the transcript its owner wrote. The gateway must not depend on the
         * session module, so the CLI composition root supplies the reader;
         * absent, a remote session's stats pills stay blank.
         */
        public Builder remoteMetrics(RemoteSessionContextPort.MetricsFold value) {
            this.remoteMetrics = Objects.requireNonNull(value, "remoteMetrics");
            return this;
        }

        public Builder settings(GatewaySettingsPort value) {
            this.settings = Objects.requireNonNull(value, "settings");
            return this;
        }

        public Builder projects(GatewayProjectsPort value) {
            this.projects = Objects.requireNonNull(value, "projects");
            return this;
        }

        public Builder schedule(GatewaySchedulePort value) {
            this.schedule = Objects.requireNonNull(value, "schedule");
            return this;
        }

        public Builder runtimeControl(GatewayRuntimeControlPort value) {
            this.runtimeControl = Objects.requireNonNull(value, "runtimeControl");
            return this;
        }

        public Builder models(GatewayModelsPort value) {
            this.models = Objects.requireNonNull(value, "models");
            return this;
        }

        public Builder commands(GatewayCommandsPort value) {
            this.commands = Objects.requireNonNull(value, "commands");
            return this;
        }

        public Builder sessionContext(GatewaySessionContextPort value) {
            this.sessionContext = Objects.requireNonNull(value, "sessionContext");
            return this;
        }

        public Builder capabilities(SessionCapabilityPort value) {
            this.capabilities = value;
            return this;
        }

        public Builder actions(GatewaySessionActionsPort value) {
            this.actions = Objects.requireNonNull(value, "actions");
            return this;
        }

        public Builder permissionMode(GatewayPermissionModePort value) {
            this.permissionMode = Objects.requireNonNull(value, "permissionMode");
            return this;
        }

        public Builder spinnerVerbs(GatewaySpinnerVerbsPort value) {
            this.spinnerVerbs = Objects.requireNonNull(value, "spinnerVerbs");
            return this;
        }

        public Builder fileSuggestions(GatewayFileSuggestionsPort value) {
            this.fileSuggestions = Objects.requireNonNull(value, "fileSuggestions");
            return this;
        }

        public Builder cancel(GatewaySessionCancelPort value) {
            this.cancel = Objects.requireNonNull(value, "cancel");
            return this;
        }

        public Builder taskBoard(GatewayTaskBoardPort value) {
            this.taskBoard = Objects.requireNonNull(value, "taskBoard");
            return this;
        }

        public Builder fileDiff(GatewayFileDiffPort value) {
            this.fileDiff = Objects.requireNonNull(value, "fileDiff");
            return this;
        }

        public Builder workspaceFile(GatewayWorkspaceFilePort value) {
            this.workspaceFile = Objects.requireNonNull(value, "workspaceFile");
            return this;
        }

        public Builder backgroundTasks(GatewayBackgroundTasksPort value) {
            this.backgroundTasks = Objects.requireNonNull(value, "backgroundTasks");
            return this;
        }

        public Builder lanGateway(
                LanGatewayConfigStore configStore, GatewayLanProxyPort proxyPort) {
            this.lanConfigStore = Objects.requireNonNull(configStore, "configStore");
            this.lanProxy = Objects.requireNonNull(proxyPort, "proxyPort");
            return this;
        }

        public Builder ccConnect(GatewayCcConnectPort value) {
            this.ccConnect = Objects.requireNonNull(value, "ccConnect");
            return this;
        }

        public Builder plugins(GatewayPluginsPort value) {
            this.plugins = Objects.requireNonNull(value, "plugins");
            return this;
        }

        public GatewayServer build() {
            return new GatewayServer(this);
        }
    }

    private GatewayServer(Builder builder) {
        Config config = builder.config;
        String token = builder.token;
        SessionHostRegistry registry = builder.registry;
        GatewaySessionCatalogPort catalog = builder.catalog;
        TranscriptSessionActions sessionActions = null;
        GatewayHeadlessSessions headless = builder.headless;
        InteractionCoordinator interactions = builder.interactions;
        GatewaySessionMessagesPort sessionMessages = builder.sessionMessages;
        GatewaySettingsPort settings = builder.settings;
        GatewaySchedulePort schedule = builder.schedule;
        GatewayModelsPort models = builder.models;
        GatewayCommandsPort commands = builder.commands;
        GatewaySessionContextPort sessionContext = builder.sessionContext;
        GatewaySessionActionsPort actions = builder.actions;
        GatewayPermissionModePort permissionMode = builder.permissionMode;
        GatewaySpinnerVerbsPort spinnerVerbs = builder.spinnerVerbs;
        GatewayFileSuggestionsPort fileSuggestions = builder.fileSuggestions;
        GatewaySessionCancelPort cancel = builder.cancel;
        GatewayTaskBoardPort taskBoard = builder.taskBoard;
        GatewayFileDiffPort fileDiff = builder.fileDiff;
        GatewayWorkspaceFilePort workspaceFile = builder.workspaceFile;
        this.config = config;
        this.auth = new GatewayAuthFilter(token);
        this.mirror = new MirrorHub(registry);
        this.remoteWorkers = builder.webWorkers != null
            ? new RemoteWorkerPool(builder.webWorkers, mirror)
            : null;
        if (builder.remoteSessionState && remoteWorkers != null) {
            sessionContext = new RemoteSessionContextPort(sessionContext,
                remoteWorkers, sessionMessages, builder.remoteMetrics);
            // The file preview follows the same sessions the context surface
            // does: a gateway that runs no engine of its own cannot resolve a
            // remote session's workspace root from its own project directory,
            // so the remote sessions it lists open their files through the work
            // directory their owning process reported.
            workspaceFile = new RemoteWorkspaceFilePort(workspaceFile, remoteWorkers);
            // The session row menu's four mutating actions follow the same
            // sessions, and are the one remote surface whose owner does not
            // matter: rename/archive/delete edit a transcript, fork derives one,
            // and all four resolve by bare id against the configuration home.
            // The composition root that starts a standalone `claude web` gateway
            // wires no session storage, so without this the four routes answered
            // "not configured" for rows the same webui had just listed.
            sessionActions = new TranscriptSessionActions(actions,
                TranscriptSessionActions.configurationHomeOf(builder.webWorkers));
            actions = sessionActions;
            // The listing follows the same rule. A gateway that serves no
            // catalog of its own listed live sessions only, so a row that
            // existed purely on disk — a fork, or a session whose TUI had
            // exited — never appeared however many turns it held, and the
            // custom title or archive flag a rename and archive had just
            // written were invisible because nothing read the transcript.
            catalog = TranscriptSessionCatalog.decorate(catalog,
                TranscriptSessionActions.configurationHomeOf(builder.webWorkers));
            // fileDiff needs no decorator here: it reads a transcript and no
            // live session state, so the gateway-side adapter never consults
            // the registry. The composition root that starts a gateway over
            // other processes' sessions therefore supplies a port that resolves
            // each transcript under the working directory its owning worker
            // reported, which is the one root that answers for every session a
            // client can address.
        }
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
        ProtocolTurnRunner.SessionResolver resolver = new ProtocolTurnRunner.SessionResolver() {
            @Override public Optional<SessionHostSession> resolve(String sessionId) {
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
            }

            @Override public Optional<CompletableFuture<Void>> submitRemote(
                    String sessionId, SessionHostSubmission submission) {
                if (remoteWorkers == null) return Optional.empty();
                return remoteWorkers.submit(sessionId, submission)
                    .map(accepted -> accepted.thenApply(_ -> null));
            }

            @Override public boolean cancelLocal(String sessionId) {
                return cancel.cancel(sessionId);
            }

            @Override public Optional<CompletableFuture<Void>> cancelRemote(String sessionId) {
                if (remoteWorkers == null) return Optional.empty();
                return remoteWorkers.cancelTurn(sessionId)
                    .map(reply -> reply.thenApply(_ -> null));
            }
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
            },
            sessionId -> remoteWorkers != null && remoteWorkers.owns(sessionId)
                ? Optional.of(new GatewaySessionsHandler.RemoteOwner(
                    remoteWorkers.pidOf(sessionId).orElse(0L),
                    remoteWorkers.workDirOf(sessionId).orElse("")))
                : Optional.empty());
        if (this.remoteWorkers != null) {
            this.remoteWorkers.setOnSessionClaimed(sessionId -> {
                // Orthogonal Matrix M5: when a live TUI /resumes a session that was
                // previously open as a local headless handle, hand off ownership cleanly
                // to the TUI so two engines never race on the same transcript.
                if (headless.isOpen(sessionId)) {
                    headless.close(sessionId);
                    mirror.detachSession(sessionId);
                    contextTimeline.forget(sessionId);
                }
            });
        }
        this.rewindApi = new GatewayRewindHandler(headless, this.remoteWorkers, inFlight::isInFlight);
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
            this.permissionsApi = new GatewayPermissionsHandler(interactions, remoteWorkers);
            // Sudo needs no presenter here: the ask is raised by the process
            // that owns the session and reaches the browser as a sudo.asked
            // mirror frame, so this endpoint only has to carry the answer
            // back to that process.
            this.sudoApi = new GatewaySudoRespondHandler(interactions, remoteWorkers);
        } else {
            this.permissionsApi = null;
            this.sudoApi = null;
        }
        GatewaySessionMessagesPort effectiveMessages = withRemoteWorkDirFallback(sessionMessages,
            sessionId -> remoteWorkers != null ? remoteWorkers.workDirOf(sessionId) : Optional.empty());
        Predicate<String> liveSessionCheck = sessionId ->
            headless.isOpen(sessionId)
                || registry.isCurrent(sessionId)
                || (remoteWorkers != null && remoteWorkers.owns(sessionId));
        this.messagesSnapshotApi = new GatewayMessagesSnapshotHandler(headless,
            effectiveMessages,
            mirror::runningSince, mirror::streamingDraft,
            liveSessionCheck);
        this.trajectoryApi = new GatewayTrajectoryHandler(
            headless,
            effectiveMessages,
            mirror::runningSince,
            mirror::streamingDraft,
            liveSessionCheck,
            builder.webWorkers != null
                ? TranscriptSessionActions.configurationHomeOf(builder.webWorkers)
                : null);
        this.settingsApi = new GatewaySettingsHandler(settings);
        this.projectsApi = new GatewayProjectsHandler(
            builder.projects,
            this.catalog,
            actions,
            headless,
            mirror,
            contextTimeline);
        this.scheduleApi = new GatewayScheduleHandler(schedule);
        this.runtimeControlApi = new GatewayRuntimeControlHandler(builder.runtimeControl);
        this.modelsApi = new GatewayModelsHandler(models);
        this.commandsApi = new GatewayCommandsHandler(commands);
        this.spinnerVerbsApi = new GatewaySpinnerVerbsHandler(spinnerVerbs);
        this.fileSuggestionsApi = new GatewayFileSuggestionsHandler(fileSuggestions);
        this.sessionContextApi = new GatewaySessionContextHandler(sessionContext, remoteWorkers);
        SessionCapabilityPort effectiveCapabilities = builder.capabilities != null
            ? builder.capabilities : headless.capabilities();
        this.capabilitiesApi = new GatewayCapabilitiesHandler(
            effectiveCapabilities, sessionContext, headless, remoteWorkers);
        this.permissionModeApi = new GatewayPermissionModeHandler(permissionMode, remoteWorkers);
        this.sessionCancelApi = new GatewaySessionCancelHandler(cancel, remoteWorkers);
        this.taskBoardApi = new GatewayTaskBoardHandler(taskBoard);
        this.changesApi = new GatewayChangesHandler(fileDiff);
        this.workspaceFileApi = new GatewayWorkspaceFileHandler(workspaceFile);
        this.backgroundTasksApi = new GatewayBackgroundTasksHandler(builder.backgroundTasks);
        this.lanConfigStore = builder.lanConfigStore;
        this.lanModelProxy = new LanModelProxyHandler(this.lanConfigStore, builder.lanProxy);
        this.currentBoundHost = Strings.CS.equals("0.0.0.0", config.host()) || this.lanConfigStore.load().enabled()
            ? "0.0.0.0" : "127.0.0.1";
        this.localMachineHostName = detectLocalHostName();
        this.lanAdminApi = new GatewayLanAdminHandler(
            this.lanConfigStore,
            this.lanModelProxy,
            builder.lanProxy,
            this.awakeGuard,
            this::port,
            this::boundHost,
            this::rebindLanSharing,
            this::withBindLock);
        this.ccConnectApi = new GatewayCcConnectHandler(bindCcConnectPort(builder.ccConnect));
        this.pluginsApi = new GatewayPluginsHandler(builder.plugins);
        this.sessionActionsApi = new GatewaySessionActionsHandler(actions);
        // The turn-completion delta folds read the same durable metrics the
        // session-context endpoint serves — one projection, two consumers.
        mirror.metricsReader(sessionContext::metrics);
        this.registrySubscription = registry.subscribe(event -> {
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
        this.router = new GatewayRouter(
            config,
            auth,
            mirror,
            messages,
            chat,
            responses,
            sessionsApi,
            sessionActionsApi,
            rewindApi,
            permissionsApi,
            sudoApi,
            messagesSnapshotApi,
            trajectoryApi,
            settingsApi,
            scheduleApi,
            runtimeControlApi,
            modelsApi,
            commandsApi,
            spinnerVerbsApi,
            fileSuggestionsApi,
            directoryPickerApi,
            projectsApi,
            sessionContextApi,
            capabilitiesApi,
            permissionModeApi,
            sessionCancelApi,
            taskBoardApi,
            changesApi,
            workspaceFileApi,
            backgroundTasksApi,
            lanModelProxy,
            lanAdminApi,
            ccConnectApi,
            pluginsApi,
            contextTimelineApi,
            localMachineHostName,
            new GatewaySessionListingProjection(catalog, registry, headless, mirror, sessionActions, remoteWorkers));
    }

    /** Binds the port and starts serving; returns once the socket is live. */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) return;
        synchronized (bindLock) {
            LanGatewayConfigStore.LanGatewayConfig lanCfg = lanConfigStore.load();
            String initialHost = Strings.CS.equals("0.0.0.0", config.host()) || lanCfg.enabled()
                ? "0.0.0.0" : "127.0.0.1";
            server = createAndStartHttpServer(initialHost, config.port());
            currentBoundHost = initialHost;
            awakeGuard.sync(lanCfg.enabled() && lanCfg.preventMacSleep());
        }
        // Other processes' sessions are followed from the moment they start,
        // not from whichever request next happens to list them.
        if (remoteWorkers != null) remoteWorkers.startWatching(RemoteWorkerPool.WATCH_INTERVAL);
    }

    /** Currently bound host ({@code "127.0.0.1"} or {@code "0.0.0.0"}). */
    public String boundHost() {
        synchronized (bindLock) {
            return currentBoundHost;
        }
    }

    void withBindLock(GatewayLanAdminHandler.IoAction action) throws IOException {
        synchronized (bindLock) {
            action.execute();
        }
    }

    /** Hot-swaps the listener between loopback and all interfaces on the same port. */
    void rebindLanSharing(boolean shareOnLan) throws IOException {
        synchronized (bindLock) {
            HttpServer current = server;
            if (current == null) return;
            String targetHost = shareOnLan ? "0.0.0.0" : "127.0.0.1";
            if (Strings.CS.equals(currentBoundHost, targetHost)) return;
            int activePort = current.getAddress().getPort();
            String previousHost = currentBoundHost;
            current.stop(0);
            try {
                server = bindWithRetry(targetHost, activePort);
                currentBoundHost = targetHost;
                log.info("Rebound gateway listener from {}:{} to {}:{}",
                    previousHost, activePort, targetHost, activePort);
            } catch (IOException failure) {
                // Restore previous listener so local Web UI stays reachable.
                try {
                    server = bindWithRetry(previousHost, activePort);
                    currentBoundHost = previousHost;
                } catch (IOException _) {
                    // Nothing further to recover.
                }
                throw failure;
            }
        }
    }

    private GatewayCcConnectPort bindCcConnectPort(GatewayCcConnectPort ccConnectPort) {
        ccConnectPort.bindRemoteBridge(new GatewayCcConnectPort.RemoteBridge() {
            @Override
            public List<GatewayCcConnectPort.LiveWorker> liveWorkers() {
                return remoteWorkers != null ? remoteWorkers.liveWorkers() : List.of();
            }

            @Override
            public Optional<GatewayCcConnectPort.CollaborationState> getCollaboration(
                    long pid, String sessionId) {
                return remoteWorkers != null
                    ? remoteWorkers.getCollaboration(pid, sessionId)
                    : Optional.empty();
            }

            @Override
            public Optional<GatewayCcConnectPort.CollaborationState> setCollaboration(
                    long pid, String sessionId, String channel, boolean enabled) {
                return remoteWorkers != null
                    ? remoteWorkers.setCollaboration(pid, sessionId, channel, enabled)
                    : Optional.empty();
            }

            @Override
            public void publishChanged() {
                mirror.publishCcConnectChanged();
            }
        });
        if (remoteWorkers != null) {
            remoteWorkers.setOnWorkerDisconnected(ccConnectPort::onWorkerDisconnected);
        }
        return ccConnectPort;
    }

    private static final int MAX_BIND_ATTEMPTS = 20;
    private static final long BIND_RETRY_DELAY_MS = 15L;

    private HttpServer bindWithRetry(String host, int targetPort) throws IOException {
        for (int attempt = 0; attempt < MAX_BIND_ATTEMPTS; attempt++) {
            try {
                return createAndStartHttpServer(host, targetPort);
            } catch (BindException bindErr) {
                if (attempt + 1 >= MAX_BIND_ATTEMPTS) {
                    throw bindErr;
                }
                pauseBeforeRetry(BIND_RETRY_DELAY_MS, bindErr);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private static void pauseBeforeRetry(long delayMs, BindException cause) throws BindException {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(delayMs));
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw cause;
        }
    }

    private HttpServer createAndStartHttpServer(String host, int targetPort) throws IOException {
        InetAddress address = Strings.CS.equals("0.0.0.0", host)
            ? InetAddress.getByName("0.0.0.0")
            : InetAddress.getLoopbackAddress();
        HttpServer created = HttpServer.create(
            new InetSocketAddress(address, targetPort), 0);
        // One virtual thread per exchange: every SSE connection parks its own
        // lane thread while REST handlers finish quickly.
        created.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        created.createContext("/", router::routeGuarded);
        created.start();
        return created;
    }

    private static String detectLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception _) {
            return "";
        }
    }

    /** The bound port — meaningful when {@code Config.port} was 0 (auto). */
    public int port() {
        synchronized (bindLock) {
            HttpServer snapshot = server;
            if (snapshot == null) throw new IllegalStateException("gateway server is not started");
            return snapshot.getAddress().getPort();
        }
    }

    /** The mirror fan-out hub, for interaction presenters to publish into. */
    public MirrorHub mirror() {
        return mirror;
    }

    @Override
    public void close() {
        awakeGuard.close();
        HttpServer snapshot;
        synchronized (bindLock) {
            snapshot = server;
            server = null;
        }
        if (snapshot != null) snapshot.stop(0);
        mirror.detach();
        contextTimeline.close();
        sessionsApi.closeAll();
        if (remoteWorkers != null) remoteWorkers.close();
        // Drop the activation feed last: the handler above can publish into
        // the mirror on its way out, and the subscription must still be live
        // to carry those frames to any lane still open.
        try {
            registrySubscription.close();
        } catch (Exception _) {
            // Nothing to recover: the registry is going away with this server.
        }
    }

    /**
     * A session hosted by another interactive process lives under that process's
     * project, so a local miss retries the transcript at the work directory the
     * worker reported for it.
     */
    static GatewaySessionMessagesPort withRemoteWorkDirFallback(
            GatewaySessionMessagesPort local, Function<String, Optional<String>> workDirOf) {
        return new GatewaySessionMessagesPort() {
            @Override public Optional<List<Message>> messages(
                    String sessionId, String headlessSessionId) {
                Optional<List<Message>> own =
                    local.messages(sessionId, headlessSessionId);
                if (own.isPresent()) return own;
                return workDirOf.apply(sessionId)
                    .flatMap(workDir -> local.messagesInWorkDir(sessionId, workDir));
            }

            @Override public Optional<List<Message>> agentMessages(
                    String sessionId, String agentId) {
                return local.agentMessages(sessionId, agentId);
            }
        };
    }

}
