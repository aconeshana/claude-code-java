package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.oauth.McpOAuthProvider;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * MCP Streamable HTTP transport (spec revision 2025-06-18).
 */
public class HttpTransport implements McpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HttpTransport.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String SESSION_ID_HEADER = "Mcp-Session-Id";

    private final String serverUrl;
    private final Map<String, String> headers;
    private final McpServerConfig serverConfig;
    private final McpOAuthProvider oauth;
    private final OkHttpClient httpClient;
    private final AtomicInteger requestId = new AtomicInteger(0);
    private volatile boolean connected = false;
    // Session id echoed back to us by the server after the first successful
// response — carried on every subsequent POST until close.
    private volatile String sessionId;

    public HttpTransport(McpServerConfig config) {
        this(config, new McpOAuthProvider(), McpHttpClient.requestResponse());
    }

    /**
     * Constructor with injectable OAuth provider — for tests and any embedder
     * that manages storage differently. Production always uses the singleton.
     */
    public HttpTransport(McpServerConfig config, McpOAuthProvider oauth) {
        this(config, oauth, McpHttpClient.requestResponse());
    }

    HttpTransport(McpServerConfig config, McpOAuthProvider oauth, OkHttpClient httpClient) {
        if (StringUtils.isBlank(config.url())) {
            throw new McpException("HTTP transport requires 'url' field for server '"
                + config.name() + "'");
        }
        this.serverUrl = config.url();
        this.headers = config.headers();
        this.serverConfig = config;
        this.oauth = Objects.requireNonNull(oauth, "oauth");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * Connects the transport. Streamable HTTP has no connection handshake —
     * the shared HTTP profile is already initialized and we only mark ready.
     * Errors surface on the first request.
     */
    public void connect() {
        LOG.info("HTTP transport ready for {}", serverConfig != null
            ? McpUtils.getLoggingSafeMcpBaseUrl(serverConfig) : serverUrl);
        connected = true;
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) {
        if (!connected) {
            throw new McpException("HTTP transport not connected to " + serverUrl);
        }

        McpJsonRpcRequests.Prepared prepared =
            McpJsonRpcRequests.prepare(requestId, method, params);
        int id = prepared.id();
        ObjectNode request = prepared.request();

        try {
            Request.Builder builder = new Request.Builder()
                    .url(serverUrl)
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                        JsonUtils.getMapper().writeValueAsString(request), JSON));
            applyHeaders(builder);
            if (sessionId != null) {
                builder.header(SESSION_ID_HEADER, sessionId);
            }
            try (Response response = McpHttpClient.executeForHeaders(
                    httpClient, builder.build(), McpTimeouts.responseHeadersTimeout())) {
                String responseSessionId = response.header(SESSION_ID_HEADER);
                if (responseSessionId != null
                    && (sessionId == null || !sessionId.equals(responseSessionId))) {
                    sessionId = responseSessionId;
                    LOG.debug("HTTP transport session id: {}", responseSessionId);
                }

                int status = response.code();
                if (status == 401) {
                    // Purge any stale stored token so the next connect surfaces
                    // "authenticate required" instead of silently retrying with
                    // the same bad bearer.
                    boolean hadToken = oauth.hasStoredToken(serverConfig);
                    if (hadToken) oauth.clearAuth(serverConfig);
                    String hint = hadToken
                        ? "stored OAuth token was rejected — /mcp → Authenticate to re-auth"
                        : "add credentials to headers or run /mcp Authenticate";
                    throw new McpException("HTTP 401 Unauthorized from " + serverUrl + " — " + hint);
                }

                ResponseBody responseBody = response.body();
                applyOperationDeadline(responseBody, McpTimeouts.operationTimeout(method));
                if (status >= 400) {
                    String body = responseBody.string();
                    throw new McpException("HTTP " + status + " from " + serverUrl
                        + ": " + truncateBody(body));
                }

                String contentType = Objects.requireNonNullElse(
                    response.header("Content-Type"), "");
                JsonNode payload;
                if (Strings.CS.contains(contentType, "text/event-stream")) {
                    payload = parseSseFrame(responseBody.source(), id);
                } else {
                    String body = responseBody.string();
                    if (StringUtils.isBlank(body)) {
                        throw new McpException("HTTP transport received empty body for " + method);
                    }
                    payload = JsonUtils.getMapper().readTree(body);
                }

                if (payload == null) {
                    throw new McpException("HTTP transport: no JSON-RPC frame with id="
                        + id + " in response");
                }
                if (payload.has("error")) {
                    throw new McpException(payload.get("error").toString());
                }
                return payload.has("result") ? payload.get("result") : payload;
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("HTTP request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts the JSON-RPC frame with the matching request id from an SSE
     * body. Streamable HTTP servers may emit multiple {@code data:} frames
     * (e.g. progress notifications) before the actual response.
     */
    private JsonNode parseSseFrame(BufferedSource source, int expectedId) throws Exception {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (Strings.CS.startsWith(line, "data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).trim());
            } else if (line.isEmpty() && !data.isEmpty()) {
                JsonNode frame = JsonUtils.getMapper().readTree(data.toString());
                if (frame.has("id") && frame.get("id").asInt() == expectedId) {
                    return frame;
                }
                data.setLength(0);
            }
        }
        if (!data.isEmpty()) {
            JsonNode frame = JsonUtils.getMapper().readTree(data.toString());
            if (frame.has("id") && frame.get("id").asInt() == expectedId) {
                return frame;
            }
        }
        return null;
    }

    private static void applyOperationDeadline(ResponseBody body, Duration timeout) {
        body.source().timeout().deadlineNanoTime(System.nanoTime() + timeout.toNanos());
    }

    /**
     * Applies configured static headers to the request, then attaches an
     * OAuth Bearer if a token is stored and the caller didn't already supply
     * an {@code Authorization} header via config (PAT overrides OAuth).
     * Reserved headers {@code Accept} / {@code Content-Type} are set by the
     * caller and skipped here to avoid Lanterna-fork-style duplicate-header
     * exceptions at build time.
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
        if (!explicitAuth) {
            Optional<String> bearer = oauth.tokenFor(serverConfig);
            bearer.ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        }
    }

    private static String truncateBody(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (!connected) {
            throw new McpException("HTTP transport not connected to " + serverUrl);
        }
        try {
            ObjectNode notif = JsonUtils.getMapper().createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", method);
            if (params != null) notif.set("params", params);
            Request.Builder builder = new Request.Builder()
                    .url(serverUrl)
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(
                        JsonUtils.getMapper().writeValueAsString(notif), JSON));
            applyHeaders(builder);
            if (sessionId != null) {
                builder.header(SESSION_ID_HEADER, sessionId);
            }
            // Fire and forget; anything the server sends back is either an ack
            // (typical 202) or a follow-up frame we don't need in this call path.
            McpHttpClient.executeAndClose(httpClient, builder.build());
        } catch (Exception e) {
            throw new McpException("Failed to send notification " + method
                + " to " + serverUrl, e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        connected = false;
        sessionId = null;
        LOG.debug("HTTP transport disconnected from {}", serverConfig != null
            ? McpUtils.getLoggingSafeMcpBaseUrl(serverConfig) : serverUrl);
    }
}
