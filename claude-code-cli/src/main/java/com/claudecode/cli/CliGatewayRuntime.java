package com.claudecode.cli;

import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.config.VersionInfo;
import com.claudecode.gateway.*;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.session.SessionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang3.StringUtils;

/**
 * Owns the in-process web gateway lifecycle for one interactive CLI run.
 *
 * <p>The gateway is started eagerly in the background at REPL startup (so the
 * welcome block can surface its URL almost immediately) and on demand by the
 * {@code /web} command: it binds the fixed loopback port {@value #DEFAULT_PORT},
 * mints a per-launch token (never persisted, never logged), and prints the
 * token-embedded URL through the TUI. Concurrent {@link #start()} calls share
 * one in-flight start — a warmup start and a {@code /web} start never bind
 * two ports. Closing the runtime stops the server with the interactive
 * session.
 *
 * <p>The port is deliberately not probed upward. A busy {@value #DEFAULT_PORT}
 * means another gateway already owns the machine's web endpoint — either a
 * standalone {@code claude web} supervisor or a peer TUI — and the answer is
 * to fail the start rather than bind a second, undiscoverable one. Discovery
 * is single-valued ({@code web-gateway.json} holds one record) and the web UI
 * is addressed by one fixed URL, so a gateway on 19846 would be reachable only
 * by whoever read the startup line: exactly the orphan-port situation the fixed
 * port prevents.
 *
 * <p>The sessions endpoint's two-level project→session listing is fed by the
 * same {@code ProjectCatalog} aggregation the TUI project panel uses
 * (via {@link CliProjectCatalogAdapter}), projected onto the gateway-owned
 * catalog port so the gateway module stays free of session-module types.
 */
@Explanation("Lifecycle owner of the on-demand web gateway endpoint")
final class CliGatewayRuntime implements GatewaySupervisorPort, AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The loopback port the gateway binds, and the only one it ever binds.
     *
     * <p>19845 is built from two emoji code points: {@code ⚡} (U+26A1 = 9889) contributes
     * its leading digits {@code 98}, {@code 🐭} (U+1F42D = 128045) its trailing digits
     * {@code 45}, and {@code 1} pads the result to five digits. It sits above 10000 and is
     * not an IANA-assigned port. The value is fixed so that "the web UI" has exactly one
     * address on a machine: the port doubles as the single-instance lock (see the class
     * javadoc).
     */
    private static final int DEFAULT_PORT = 19845;

    /**
     * Environment override for the port, and only for tests.
     *
     * <p>The fixed port is the single-instance rule, so the default must never be
     * changed to dodge a collision — that is the behaviour this class exists to
     * prevent. The tests that prove the rule need the opposite: a port no other
     * gateway on the developer's machine occupies, so that they test the rule
     * instead of rediscovering the developer's running gateway. Unset, blank, or
     * unparseable leaves the default in place.
     */
    static final String PORT_OVERRIDE_ENV = "CLAUDE_WEB_PORT";

    private final SessionHostRegistry registry;
    private final GatewaySessionCatalogPort catalog;
    private final CliHeadlessGatewaySessions headless;
    private final InteractionCoordinator interactions;
    private final GatewaySessionMessagesPort sessionMessages;
    private final GatewaySettingsPort settings;
    private final GatewaySchedulePort schedule;
    private final GatewayModelsPort models;
    private final GatewayCommandsPort commands;
    private final GatewaySessionContextPort sessionContext;
    private final GatewaySessionActionsPort actions;
    private final GatewayPermissionModePort permissionMode;
    private final GatewaySpinnerVerbsPort spinnerVerbs;
    private final GatewayFileSuggestionsPort fileSuggestions;
    private final GatewaySessionCancelPort sessionCancel;
    private final GatewayTaskBoardPort taskBoard;
    private final GatewayFileDiffPort fileDiff;
    private final GatewayWorkspaceFilePort workspaceFile;
    private final GatewayBackgroundTasksPort backgroundTasks;
    private volatile GatewayRuntimeControlPort runtimeControl =
        new GatewayRuntimeControlPort() {};
    private volatile GatewayCcConnectPort ccConnect =
        new GatewayCcConnectPort() {};
    /** One fold for the gateway's life, so a replay is reused until its transcript changes. */
    private final CliTranscriptMetricsFold remoteMetrics = new CliTranscriptMetricsFold();
    /**
     * Single-flight start state: null = never started, pending = start in
     * progress, done = running (or failed-and-resettable). Every concurrent
     * caller awaits the same future, so warmup and {@code /web} can never
     * race into two bindings.
     */
    private final AtomicReference<CompletableFuture<Started>> startFlight = new AtomicReference<>();
    private Path gatewayRecordFile = ClaudePaths.WEB_GATEWAY_JSON;
    private Path webWorkersDirectory = ClaudePaths.WEB_WORKERS_DIR;
    private Path lanGatewayFile = ClaudePaths.LAN_GATEWAY_JSON;
    private volatile GatewayServer server;
    private volatile String url;

    CliGatewayRuntime(SessionHostRegistry registry) {
        this(registry, new GatewaySessionCatalogPort() {});
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog) {
        this(registry, catalog, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless) {
        this(registry, catalog, headless, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions) {
        this(registry, catalog, headless, interactions, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages) {
        this(registry, catalog, headless, interactions, sessionMessages, null, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule) {
        this(registry, catalog, headless, interactions, sessionMessages, settings, schedule, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, new GatewayCommandsPort() {});
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, new GatewaySessionContextPort() {});
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, sessionContext, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, sessionContext, actions, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions,
            GatewayPermissionModePort permissionMode) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, sessionContext, actions, permissionMode, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions,
            GatewayPermissionModePort permissionMode, GatewaySpinnerVerbsPort spinnerVerbs) {
        this(registry, catalog, headless, interactions, sessionMessages,
            settings, schedule, models, commands, sessionContext, actions, permissionMode,
            spinnerVerbs, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions,
            GatewayPermissionModePort permissionMode, GatewaySpinnerVerbsPort spinnerVerbs,
            GatewayFileSuggestionsPort fileSuggestions) {
        this(registry, catalog, headless, interactions, sessionMessages, settings, schedule,
            models, commands, sessionContext, actions, permissionMode, spinnerVerbs,
            fileSuggestions, null, null, null, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions,
            GatewayPermissionModePort permissionMode, GatewaySpinnerVerbsPort spinnerVerbs,
            GatewayFileSuggestionsPort fileSuggestions, GatewaySessionCancelPort sessionCancel,
            GatewayTaskBoardPort taskBoard, GatewayFileDiffPort fileDiff,
            GatewayWorkspaceFilePort workspaceFile) {
        this(registry, catalog, headless, interactions, sessionMessages, settings, schedule,
            models, commands, sessionContext, actions, permissionMode, spinnerVerbs,
            fileSuggestions, sessionCancel, taskBoard, fileDiff, workspaceFile, null);
    }

    CliGatewayRuntime(SessionHostRegistry registry, GatewaySessionCatalogPort catalog,
            CliHeadlessGatewaySessions headless, InteractionCoordinator interactions,
            GatewaySessionMessagesPort sessionMessages,
            GatewaySettingsPort settings, GatewaySchedulePort schedule,
            GatewayModelsPort models, GatewayCommandsPort commands,
            GatewaySessionContextPort sessionContext, GatewaySessionActionsPort actions,
            GatewayPermissionModePort permissionMode, GatewaySpinnerVerbsPort spinnerVerbs,
            GatewayFileSuggestionsPort fileSuggestions, GatewaySessionCancelPort sessionCancel,
            GatewayTaskBoardPort taskBoard, GatewayFileDiffPort fileDiff,
            GatewayWorkspaceFilePort workspaceFile,
            GatewayBackgroundTasksPort backgroundTasks) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.headless = headless;
        this.interactions = interactions;
        this.sessionMessages = sessionMessages;
        this.settings = settings;
        this.schedule = schedule;
        this.models = models;
        this.commands = commands;
        this.sessionContext = sessionContext;
        this.actions = actions;
        this.permissionMode = permissionMode;
        this.spinnerVerbs = spinnerVerbs;
        this.fileSuggestions = fileSuggestions;
        this.sessionCancel = sessionCancel;
        this.taskBoard = taskBoard;
        this.fileDiff = fileDiff;
        this.workspaceFile = workspaceFile;
        this.backgroundTasks = backgroundTasks;
    }

    @Override
    public Started start() {
        CompletableFuture<Started> flight = startFlight.getAndUpdate(current ->
            current != null ? current : new CompletableFuture<>());
        if (flight == null) {
            // We created the flight: bind here, synchronously, and resolve it
            // for every waiter. A failed bind clears the flight so a later
            // call can retry.
            flight = runBind(startFlight.get());
        }
        return flight.join();
    }

    @Override
    public CompletableFuture<Started> startAsync() {
        CompletableFuture<Started> created = new CompletableFuture<>();
        CompletableFuture<Started> flight = startFlight.getAndUpdate(current ->
            current != null ? current : created);
        if (flight != null) {
            // Already running or in flight: await the same start.
            return flight;
        }
        // We own the new flight: bind off the calling thread. The binding
        // thread is implementation-owned — startAsync must never rely on the
        // caller to resolve the flight it registered (a caller that only
        // awaits the future would deadlock against itself).
        Thread.ofVirtual().name("web-gateway-bind").start(() -> runBind(created));
        return created;
    }

    /**
     * Performs the binding for the already-registered {@code flight} and
     * resolves it. A failed bind clears the flight so the next call retries.
     */
    private CompletableFuture<Started> runBind(CompletableFuture<Started> flight) {
        try {
            flight.complete(bind());
        } catch (RuntimeException | IOException failure) {
            startFlight.compareAndSet(flight, null);
            flight.completeExceptionally(
                new IllegalStateException("failed to start web gateway", failure));
        }
        return flight;
    }

    void setRuntimeControl(GatewayRuntimeControlPort control) {
        this.runtimeControl = control != null
            ? control
            : new GatewayRuntimeControlPort() {};
    }

    void setCcConnect(GatewayCcConnectPort ccConnectPort) {
        this.ccConnect = ccConnectPort != null
            ? ccConnectPort
            : new GatewayCcConnectPort() {};
    }

    private Started bind() throws IOException {
        GatewayServer existing = server;
        if (existing != null) {
            return new Started(url);
        }
        Optional<WebGatewayRecord> global = WebGatewayRecord.readLive(gatewayRecordFile);
        if (global.isPresent()) {
            return new Started(global.get().url());
        }
        String token = newToken();
        // Bind the one fixed port. A collision is not retried on a neighbouring
        // port: it means a peer gateway already owns the machine's web endpoint.
        int port = configuredPort();
        LanGatewayConfigStore lanConfigStore = new LanGatewayConfigStore(lanGatewayFile);
        String bindHost = lanConfigStore.load().enabled() ? "0.0.0.0" : "127.0.0.1";
        GatewayLanProxyPort lanProxyPort = headless != null
            ? headless.lanProxyPort()
            : CliLanProxyAdapter.catalogOnly(new CustomModelJsonStore());
        GatewayServer created = GatewayServer.builder(
            new GatewayServer.Config(bindHost, port), token, registry)
            .catalog(catalog)
            .headless(headless != null ? headless : new GatewayHeadlessSessions() {})
            .interactions(interactions)
            .sessionMessages(sessionMessages != null ? sessionMessages
                : new GatewaySessionMessagesPort() {})
            .settings(settings != null ? settings : new GatewaySettingsPort() {})
            .projects(new CliProjectsAdapter())
            .schedule(schedule != null ? schedule : new GatewaySchedulePort() {})
            .models(models != null ? models : new GatewayModelsPort() {})
            .commands(commands != null ? commands : new GatewayCommandsPort() {})
            .sessionContext(sessionContext != null ? sessionContext : new GatewaySessionContextPort() {})
            .actions(actions != null ? actions : new GatewaySessionActionsPort() {})
            .permissionMode(permissionMode != null ? permissionMode : new GatewayPermissionModePort() {})
            .spinnerVerbs(spinnerVerbs != null ? spinnerVerbs : new GatewaySpinnerVerbsPort() {})
            .fileSuggestions(fileSuggestions != null ? fileSuggestions : new GatewayFileSuggestionsPort() {})
            .cancel(sessionCancel != null ? sessionCancel : _ -> false)
            .taskBoard(taskBoard != null ? taskBoard : _ -> Optional.empty())
            .fileDiff(fileDiff != null ? fileDiff : GatewayFileDiffPort.none())
            .workspaceFile(workspaceFile != null ? workspaceFile : GatewayWorkspaceFilePort.none())
            .backgroundTasks(backgroundTasks != null ? backgroundTasks : new GatewayBackgroundTasksPort() {})
            .runtimeControl(runtimeControl)
            .lanGateway(lanConfigStore, lanProxyPort)
            .ccConnect(ccConnect)
            .webWorkers(new WebWorkerDirectory(webWorkersDirectory))
            // Every session a webui client addresses belongs to this process or
            // to another TUI on the machine; the gateway forwards the halves
            // only the owning process knows and replays the transcript halves.
            .remoteSessionState(true)
            // Addressed by the owner's work directory: this process's own
            // project holds none of the transcripts it is asked to fold.
            .remoteMetrics((sessionId, workDir) -> remoteMetrics.of(sessionId,
                new SessionManager(workDir).getSessionFile(sessionId)))
            .build();
        created.start();
        String published = "http://127.0.0.1:" + created.port() + "/?token=" + token;
        try {
            WebGatewayRecord.write(gatewayRecordFile, new WebGatewayRecord(
                ProcessHandle.current().pid(), published,
                VersionInfo.version(), System.currentTimeMillis()));
        } catch (IOException failure) {
            created.close();
            throw failure;
        }
        server = created;
        url = published;
        return new Started(url);
    }

    /** Points the discovery record and worker directory under {@code claudeHome}; tests isolate state here. */
    void useWebStateDirectory(Path claudeHome) {
        gatewayRecordFile = claudeHome.resolve(ClaudePaths.WEB_GATEWAY_JSON.getFileName());
        webWorkersDirectory = claudeHome.resolve(ClaudePaths.WEB_WORKERS_DIR.getFileName());
        lanGatewayFile = claudeHome.resolve(ClaudePaths.LAN_GATEWAY_JSON.getFileName());
    }

    /**
     * The running server's mirror hub, or null when the gateway is not up.
     * Publishers that fan state changes out to mirror clients (e.g. a
     * permission-mode broadcast) drop their frame while this is null.
     */
    MirrorHub mirrorOrNull() {
        GatewayServer current = server;
        return current == null ? null : current.mirror();
    }

    @Override
    public void close() {
        CompletableFuture<Started> flight = startFlight.getAndSet(null);
        if (flight != null && !flight.isDone()) {
            flight.cancel(false);
        }
        GatewayServer current = server;
        server = null;
        url = null;
        if (current != null) {
            current.close();
            WebGatewayRecord.removeIfOwnedBy(gatewayRecordFile, ProcessHandle.current().pid());
        }
        if (headless != null) headless.closeAll();
    }

    private static String newToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    /**
     * The port to bind: the fixed default, or the test override when one is set.
     *
     * <p>There is no probing and no fallback whichever value is in play — an
     * occupied port fails the start, which is the property the single-instance
     * rule is made of.
     */
    static int configuredPort() {
        String override = System.getenv(PORT_OVERRIDE_ENV);
        if (StringUtils.isBlank(override)) return DEFAULT_PORT;
        try {
            int parsed = Integer.parseInt(override.trim());
            return parsed > 0 && parsed <= 65535 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException _) {
            return DEFAULT_PORT;
        }
    }
}
