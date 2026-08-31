package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * MCP transport over HTTP Server-Sent Events (SSE) — the legacy dual-endpoint pattern from MCP spec
 * 2024-11-05.
 */
public class SseTransport implements McpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(SseTransport.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String serverUrl;
    private final Map<String, String> headers;
    private final McpServerConfig serverConfig;
    private final McpOAuthProvider oauth;
    private final OkHttpClient postClient;
    private final OkHttpClient streamClient;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private volatile boolean connected = false;
    private volatile String postEndpoint;
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pending
        = new ConcurrentHashMap<>();
    /**
     * Server-initiated request handlers keyed by JSON-RPC method. Populated
     * lazily via {@link #onServerRequest} — {@link McpClientManager} calls
     * that after a successful {@link #connect} to register e.g. the
     * {@code roots/list} handler.
     */
    private final ConcurrentHashMap<String, ServerRequestHandler> serverRequestHandlers
        = new ConcurrentHashMap<>();
    /**
     * Server-initiated notification handlers keyed by method. Populated by
     * {@link #onNotification} — typical use is
     * {@code notifications/tools/list_changed} → refresh tool registry.
     */
    private final ConcurrentHashMap<String, NotificationHandler> notificationHandlers
        = new ConcurrentHashMap<>();
    private Thread sseThread;
    private volatile Call sseCall;

    /**
     * Builds a transport from the full server config. The URL must be present
     * on the config (validated at connect-time by {@link McpClientManager}).
     */
    public SseTransport(McpServerConfig config) {
        this(config, new McpOAuthProvider(),
            McpHttpClient.requestResponse(), McpHttpClient.eventStream());
    }

    /** Constructor with injectable OAuth provider — used by tests. */
    public SseTransport(McpServerConfig config, McpOAuthProvider oauth) {
        this(config, oauth, McpHttpClient.requestResponse(), McpHttpClient.eventStream());
    }

    SseTransport(McpServerConfig config, McpOAuthProvider oauth,
                 OkHttpClient postClient, OkHttpClient streamClient) {
        if (StringUtils.isBlank(config.url())) {
            throw new McpException("SSE transport requires 'url' field for server '"
                + config.name() + "'");
        }
        this.serverUrl = config.url();
        this.headers = config.headers();
        this.serverConfig = config;
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.postClient = Objects.requireNonNull(postClient, "postClient");
        this.streamClient = Objects.requireNonNull(streamClient, "streamClient");
    }

    /**
     * Legacy constructor kept only for the old test that used {@code command}
     * as the URL. New code should pass a full config.
     *
     * @deprecated M1b-α; use {@link #SseTransport(McpServerConfig)}.
     */
    @Deprecated
    public SseTransport(String serverUrl) {
        this.serverUrl = serverUrl;
        this.headers = Map.of();
        this.serverConfig = null;
        this.oauth = null;
        this.postClient = McpHttpClient.requestResponse();
        this.streamClient = McpHttpClient.eventStream();
    }

    /**
     * Connects to the SSE endpoint. Starts listening for events.
     */
    public void connect() {
        LOG.info("SSE transport connecting to {}", serverConfig != null
            ? McpUtils.getLoggingSafeMcpBaseUrl(serverConfig) : serverUrl);
        // Start SSE listener in background
        connected = true;
        sseThread = Thread.ofVirtual().start(this::listenSse);
    }

    private void listenSse() {
        Request.Builder builder = new Request.Builder()
                    .url(serverUrl)
                    .header("Accept", "text/event-stream")
                    .get();
        applyHeaders(builder);
        Call call = streamClient.newCall(builder.build());
        sseCall = call;
        try (Response response = call.execute()) {
            if (response.code() != 200) {
                LOG.error("SSE connection failed: HTTP {}", response.code());
                connected = false;
                return;
            }

            ResponseBody body = response.body();
            parseSseEvents(body);
        } catch (Exception e) {
            if (connected) {
                LOG.error("SSE listener error: {}", e.getMessage());
                connected = false;
            } else {
                LOG.debug("SSE listener stopped");
            }
        } finally {
            sseCall = null;
        }
    }

    private void parseSseEvents(ResponseBody body) throws Exception {
        String eventType = null;
        StringBuilder data = new StringBuilder();

        String line;
        var source = body.source();
        while ((line = source.readUtf8Line()) != null) {
            if (Strings.CS.startsWith(line, "event:")) {
                eventType = line.substring(6).trim();
            } else if (Strings.CS.startsWith(line, "data:")) {
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                handleSseEvent(eventType, data.toString());
                eventType = null;
                data.setLength(0);
            }
        }
        // Handle last event if no trailing newline
        if (!data.isEmpty()) {
            handleSseEvent(eventType, data.toString());
        }
    }

    private void handleSseEvent(String eventType, String data) {
        try {
            if (Strings.CS.equals("endpoint", eventType)) {
                // Server tells us where to POST requests
                postEndpoint = data;
                LOG.debug("SSE post endpoint: {}", postEndpoint);
            } else if (Strings.CS.equals("message", eventType) || eventType == null) {
                JsonNode node = JsonUtils.getMapper().readTree(data);
                McpMessageDispatcher.dispatch(
                    node, pending, serverRequestHandlers, notificationHandlers,
                    this::sendReplyPost);
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse SSE event: {}", e.getMessage());
        }
    }

    /**
     * Posts a JSON-RPC reply object back to the server's POST endpoint. Used
     * by {@link McpMessageDispatcher} when a server-initiated request needs
     * a response. Errors are logged but not thrown — the transport can't
     * usefully react to reply-send failures beyond noting them.
     */
    private void sendReplyPost(ObjectNode reply) {
        if (!connected) return;
        String endpoint = postEndpoint != null ? postEndpoint : serverUrl;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                        JsonUtils.getMapper().writeValueAsString(reply), JSON));
            applyHeaders(builder);
            McpHttpClient.executeAndClose(postClient, builder.build());
        } catch (Exception e) {
            LOG.warn("Failed to send MCP reply: {}", e.getMessage());
        }
    }

    @Override
    public void onServerRequest(String method, ServerRequestHandler handler) {
        if (method == null || handler == null) return;
        serverRequestHandlers.put(method, handler);
    }

    @Override
    public void onNotification(String method, NotificationHandler handler) {
        if (method == null || handler == null) return;
        notificationHandlers.put(method, handler);
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) {
        if (!connected) {
            throw new McpException(
                "SSE transport not connected to " + serverUrl);
        }

        McpJsonRpcRequests.Prepared prepared =
            McpJsonRpcRequests.prepare(requestId, method, params);
        int id = prepared.id();
        ObjectNode request = prepared.request();

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        Duration operationTimeout = McpTimeouts.operationTimeout(method);
        long operationDeadline = System.nanoTime() + operationTimeout.toNanos();

        try {
            String endpoint = postEndpoint != null ? postEndpoint : serverUrl;
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                        JsonUtils.getMapper().writeValueAsString(request), JSON));
            applyHeaders(builder);
            try (Response response = McpHttpClient.executeForHeaders(
                    postClient, builder.build(), McpTimeouts.responseHeadersTimeout())) {
                if (response.code() != 200 && response.code() != 202) {
                    pending.remove(id);
                    throw new McpException("HTTP POST failed: " + response.code());
                }

                // If the response body contains the result directly
                response.body().source().timeout().deadlineNanoTime(operationDeadline);
                String body = response.body().string();
                if (!StringUtils.isBlank(body)) {
                    JsonNode respNode = JsonUtils.getMapper().readTree(body);
                    if (respNode.has("result")) {
                        pending.remove(id);
                        return respNode.get("result");
                    }
                }
            }

            // Otherwise wait for SSE event
            long remainingNanos = operationDeadline - System.nanoTime();
            if (remainingNanos <= 0) throw new TimeoutException();
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (McpException e) {
            pending.remove(id);
            throw e;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException("SSE request " + method + " timed out after "
                + operationTimeout.toMillis() + "ms", e);
        } catch (Exception e) {
            pending.remove(id);
            throw new McpException(
                "SSE request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (!connected) {
            throw new McpException("SSE transport not connected to " + serverUrl);
        }
        try {
            ObjectNode notif = JsonUtils.getMapper().createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", method);
            if (params != null) notif.set("params", params);
            String endpoint = postEndpoint != null ? postEndpoint : serverUrl;
            Request.Builder builder = new Request.Builder()
                    .url(endpoint)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                        JsonUtils.getMapper().writeValueAsString(notif), JSON));
            applyHeaders(builder);
            // Fire and forget; a 200/202 is fine, anything else we log and move on
            // (notifications have no reply-slot semantics to fail).
            McpHttpClient.executeAndClose(postClient, builder.build());
        } catch (Exception e) {
            throw new McpException("Failed to send notification " + method
                + " to " + serverUrl, e);
        }
    }

    /**
     * Applies configured static headers to the request, then attaches an
     * OAuth Bearer if a token is stored and the caller didn't already supply
     * an {@code Authorization} header via config. Reserved header names
     * ({@code Accept}, {@code Content-Type}) are skipped since they're set
     * explicitly by the caller.
     */
    private void applyHeaders(Request.Builder builder) {
        boolean explicitAuth = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (Strings.CI.equals(key, "Accept") || Strings.CI.equals(key, "Content-Type")) {
                continue;
            }
            if (Strings.CI.equals(key, "Authorization")) {
                explicitAuth = true;
            }
            builder.header(key, entry.getValue());
        }
        if (!explicitAuth && oauth != null && serverConfig != null) {
            Optional<String> bearer = oauth.tokenFor(serverConfig);
            bearer.ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() throws Exception {
        connected = false;
        pending.values().forEach(f ->
            f.completeExceptionally(
                new McpException("Transport closed")));
        pending.clear();
        if (sseThread != null) {
            Call call = sseCall;
            if (call != null) call.cancel();
            sseThread.interrupt();
        }
        LOG.debug("SSE transport disconnected from {}", serverConfig != null
            ? McpUtils.getLoggingSafeMcpBaseUrl(serverConfig) : serverUrl);
    }
}
