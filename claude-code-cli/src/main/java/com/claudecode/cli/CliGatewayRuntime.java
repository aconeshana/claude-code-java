package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewayModelsPort;
import com.claudecode.gateway.GatewaySchedulePort;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.gateway.GatewaySessionCatalogPort;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the in-process web gateway lifecycle for one interactive CLI run.
 *
 * <p>The gateway is started on demand by the {@code /web} command: it binds
 * a fixed loopback port (default {@value #DEFAULT_PORT}), probing upward
 * when that port is busy, mints a per-launch token (never persisted, never
 * logged), and prints the token-embedded URL through the TUI. Closing the
 * runtime stops the server with the interactive session.
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
    private final AtomicBoolean started = new AtomicBoolean();
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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.headless = headless;
        this.interactions = interactions;
        this.sessionMessages = sessionMessages;
        this.settings = settings;
        this.schedule = schedule;
        this.models = models;
    }

    @Override
    public Started start() {
        GatewayServer existing = server;
        if (existing != null) {
            return new Started(url);
        }
        if (!started.compareAndSet(false, true)) {
            return new Started(url);
        }
        try {
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
                models != null ? models : new GatewayModelsPort() {});
            created.start();
            server = created;
            url = "http://127.0.0.1:" + created.port() + "/?token=" + token;
            return new Started(url);
        } catch (RuntimeException | IOException failure) {
            started.set(false);
            throw new IllegalStateException("failed to start web gateway", failure);
        }
    }

    @Override
    public void close() {
        started.set(false);
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
            } catch (IOException busy) {
                // Port in use (or otherwise unbindable); try the next one.
            }
        }
        throw new IllegalStateException("Cannot find available port in range "
            + startPort + "-" + (startPort + maxAttempts - 1));
    }
}
