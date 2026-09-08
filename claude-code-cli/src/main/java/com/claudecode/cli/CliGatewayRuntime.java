package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewayServer;
import com.claudecode.gateway.GatewaySessionCatalogPort;
import com.claudecode.gateway.GatewaySessionMessagesPort;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the in-process web gateway lifecycle for one interactive CLI run.
 *
 * <p>The gateway is started on demand by the {@code /web} command: it binds
 * an auto-assigned loopback port, mints a per-launch token (never persisted,
 * never logged), and prints the token-embedded URL through the TUI. Closing
 * the runtime stops the server with the interactive session.
 *
 * <p>The sessions endpoint's two-level project→session listing is fed by the
 * same {@code ProjectCatalog} aggregation the TUI project panel uses
 * (via {@link CliProjectCatalogAdapter}), projected onto the gateway-owned
 * catalog port so the gateway module stays free of session-module types.
 */
@Explanation("Lifecycle owner of the on-demand web gateway endpoint")
final class CliGatewayRuntime implements GatewaySupervisorPort, AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionHostRegistry registry;
    private final GatewaySessionCatalogPort catalog;
    private final CliHeadlessGatewaySessions headless;
    private final InteractionCoordinator interactions;
    private final GatewaySessionMessagesPort sessionMessages;
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
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.headless = headless;
        this.interactions = interactions;
        this.sessionMessages = sessionMessages;
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
            GatewayServer created = new GatewayServer(
                new GatewayServer.Config("127.0.0.1", 0), token, registry, catalog,
                headless != null ? headless : new GatewayHeadlessSessions() {},
                interactions,
                sessionMessages != null ? sessionMessages
                    : new GatewaySessionMessagesPort() {});
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
}
