package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.gateway.GatewayCommandsPort;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewayModelsPort;
import com.claudecode.gateway.GatewaySchedulePort;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.gateway.GatewaySessionActionsPort;
import com.claudecode.gateway.GatewaySessionCatalogPort;
import com.claudecode.gateway.GatewaySessionContextPort;
import com.claudecode.gateway.GatewaySessionMessagesPort;
import com.claudecode.gateway.GatewaySettingsPort;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the in-process web gateway lifecycle for one interactive CLI run.
 *
 * <p>The gateway is started eagerly in the background at REPL startup (so the
 * welcome block can surface its URL almost immediately) and on demand by the
 * {@code /web} command: it binds a fixed loopback port (default
 * {@value #DEFAULT_PORT}), probing upward when that port is busy, mints a
 * per-launch token (never persisted, never logged), and prints the
 * token-embedded URL through the TUI. Concurrent {@link #start()} calls share
 * one in-flight start — a warmup start and a {@code /web} start never bind
 * two ports. Closing the runtime stops the server with the interactive
 * session.
 *
 * <p>The sessions endpoint's two-level project→session listing is fed by the
 * same {@code ProjectCatalog} aggregation the TUI project panel uses
 * (via {@link CliProjectCatalogAdapter}), projected onto the gateway-owned
 * catalog port so the gateway module stays free of session-module types.
 */
@Explanation("Lifecycle owner of the on-demand web gateway endpoint")
final class CliGatewayRuntime implements GatewaySupervisorPort, AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Default loopback port the gateway tries first, mirroring kimi-cli's {@code kimi web}. */
    private static final int DEFAULT_PORT = 8087;

    /** How many ascending ports to probe before giving up. */
    private static final int PORT_SEARCH_ATTEMPTS = 10;

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
    /**
     * Single-flight start state: null = never started, pending = start in
     * progress, done = running (or failed-and-resettable). Every concurrent
     * caller awaits the same future, so warmup and {@code /web} can never
     * race into two bindings.
     */
    private final AtomicReference<CompletableFuture<Started>> startFlight = new AtomicReference<>();
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

    private Started bind() throws IOException {
        GatewayServer existing = server;
        if (existing != null) {
            return new Started(url);
        }
        String token = newToken();
        int port = findAvailablePort("127.0.0.1", DEFAULT_PORT, PORT_SEARCH_ATTEMPTS);
        GatewayServer created = new GatewayServer(
            new GatewayServer.Config("127.0.0.1", port), token, registry, catalog,
            headless != null ? headless : new GatewayHeadlessSessions() {},
            interactions,
            sessionMessages != null ? sessionMessages
                : new GatewaySessionMessagesPort() {},
            settings != null ? settings : new GatewaySettingsPort() {},
            schedule != null ? schedule : new GatewaySchedulePort() {},
            models != null ? models : new GatewayModelsPort() {},
            commands != null ? commands : new GatewayCommandsPort() {},
            sessionContext != null ? sessionContext : new GatewaySessionContextPort() {},
            actions != null ? actions : new GatewaySessionActionsPort() {});
        created.start();
        server = created;
        url = "http://127.0.0.1:" + created.port() + "/?token=" + token;
        return new Started(url);
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
        if (current != null) current.close();
        if (headless != null) headless.closeAll();
    }

    private static String newToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    /**
     * Finds a bindable port starting at {@code startPort}, probing upward by
     * one on each collision. Mirrors kimi-cli's {@code find_available_port}
     * (bind-then-release probe, ascending offsets, bounded attempts).
     */
    private static int findAvailablePort(String host, int startPort, int maxAttempts) {
        for (int offset = 0; offset < maxAttempts; offset++) {
            int candidate = startPort + offset;
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress(host, candidate));
                return candidate;
            } catch (IOException _) {
                // Port in use (or otherwise unbindable); try the next one.
            }
        }
        throw new IllegalStateException("Cannot find available port in range "
            + startPort + "-" + (startPort + maxAttempts - 1));
    }
}
