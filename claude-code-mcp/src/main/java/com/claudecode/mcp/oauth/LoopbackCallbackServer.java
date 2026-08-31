package com.claudecode.mcp.oauth;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.mcp.McpException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs a one-shot HTTP server on {@code 127.0.0.1:{dynamic-port}} to receive the OAuth
 * authorization-code redirect.
 */
public final class LoopbackCallbackServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LoopbackCallbackServer.class);

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    /**
     * Starts the server. Immediately listens for exactly one {@code /callback}
     * hit; subsequent hits after the first return HTTP 410 Gone.
     *
     * @param expectedState the {@code state} value the browser must echo back;
     *                      pass the one from {@link AuthorizeUrlBuilder.BuiltUrl#state}
     */
    public LoopbackCallbackServer(String expectedState) {
        this(expectedState, findAvailablePort());
    }

    LoopbackCallbackServer(String expectedState, int requestedPort) {
        try {
            this.server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
            this.port = server.getAddress().getPort();
        } catch (IOException e) {
            throw new McpException("Failed to bind loopback callback server: " + e.getMessage(), e);
        }

        server.createContext("/callback", exchange -> {
            try {
                if (codeFuture.isDone()) {
                    respond(exchange, 410, "Authorization already delivered");
                    return;
                }
                Map<String, String> params = parseQuery(exchange.getRequestURI());
                String error = params.get("error");
                if (error != null) {
                    String desc = params.getOrDefault("error_description", "");
                    respond(exchange, 400, "Authorization failed: " + error
                        + (StringUtils.isBlank(desc) ? "" : " — " + desc));
                    codeFuture.completeExceptionally(new McpException(
                        "OAuth authorize failed: " + error + " " + desc));
                    return;
                }
                String state = params.get("state");
                if (expectedState != null && !expectedState.equals(state)) {
                    respond(exchange, 400, "State mismatch — possible CSRF, closing.");
                    codeFuture.completeExceptionally(new McpException(
                        "OAuth state mismatch: expected=" + expectedState + " actual=" + state));
                    return;
                }
                String code = params.get("code");
                if (StringUtils.isBlank(code)) {
                    respond(exchange, 400, "No authorization code received.");
                    codeFuture.completeExceptionally(
                        new McpException("OAuth callback did not include a code parameter"));
                    return;
                }
                respond(exchange, 200,
                    "Authorization successful. You can close this tab and return to the terminal.");
                codeFuture.complete(code);
            } catch (Exception e) {
                LOG.warn("Callback handler crashed: {}", e.getMessage());
                if (!codeFuture.isDone()) {
                    codeFuture.completeExceptionally(e);
                }
            }
        });

        // Single-thread executor is fine — we only ever handle one request.
        server.setExecutor(null);
        server.start();
        LOG.info("Loopback OAuth callback server listening on port {}", port);
    }

    /**
     * Blocks up to {@code timeout} waiting for the authorization code.
     *
     * @throws McpException if the flow errors out (state mismatch, provider
     *                      returned {@code error}, timeout, cancel).
     */
    public String awaitCode(Duration timeout) {
        try {
            return codeFuture.get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException _) {
            codeFuture.cancel(true);
            throw new McpException("Timed out waiting for OAuth callback after " + timeout);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while waiting for OAuth callback");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) throw me;
            throw new McpException("OAuth callback wait failed: " + e.getMessage(), e);
        }
    }

    /**
     * The exact redirect_uri to advertise to the authorization server. Callers
     * MUST pass this same string when building the authorize URL.
     */
    public String redirectUri() {
        return "http://localhost:" + port + "/callback";
    }

    public int port() {
        return port;
    }

    static int findAvailablePort() {
        String configured = SubprocessEnvironment.get(
            "MCP_OAUTH_CALLBACK_PORT");
        if (configured != null) {
            try {
                int port = Integer.parseInt(configured);
                if (port > 0) return port;
            } catch (NumberFormatException _) {
// Invalid values are treated as unset, matching the compatibility helper.
            }
        }
        for (int attempt = 0; attempt < 100; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(49152, 65536);
            if (isAvailable(candidate)) return candidate;
        }
        if (isAvailable(3118)) return 3118;
        throw new McpException("No available ports for OAuth redirect");
    }

    private static boolean isAvailable(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    @Override
    public void close() {
        // stop(0) = don't wait for in-flight exchanges.
        server.stop(0);
        if (!codeFuture.isDone()) {
            codeFuture.cancel(true);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, String> parseQuery(URI uri) {
        String q = uri.getRawQuery();
        Map<String, String> out = new HashMap<>();
        if (StringUtils.isBlank(q)) return out;
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(
                URLDecoder.decode(k, StandardCharsets.UTF_8),
                URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void respond(HttpExchange exchange, int status, String body)
        throws IOException {
        byte[] bytes = ("<html><body style='font:14px system-ui;padding:32px'>"
            + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
