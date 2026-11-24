package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJson;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * HTTP handlers for {@code /api/cc-connect/**}: status snapshot, per-TUI attach/detach,
 * sidecar log inspection, and streaming Feishu onboarding setup.
 */
final class GatewayCcConnectHandler {

    private static final int DEFAULT_LOG_TAIL = 200;
    private static final int MAX_LOG_TAIL = 1_000;

    private final GatewayCcConnectPort port;

    GatewayCcConnectHandler(GatewayCcConnectPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    void handleGetStatus(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, serializeSnapshot(port.snapshot()));
    }

    void handleAttach(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null) return;
        long pid = body.path("pid").asLong(0L);
        String sessionId = body.path("session_id").asText("").trim();
        String channel = body.path("channel").asText("feishu").trim();
        String origin = body.path("origin").asText("web").trim();
        if (pid <= 0 && StringUtils.isBlank(sessionId)) {
            respondJson(exchange, 400, errorBody("invalid_request", "pid or session_id is required"));
            return;
        }
        try {
            GatewayCcConnectPort.Snapshot updated = port.attach(
                new GatewayCcConnectPort.AttachRequest(pid, sessionId, channel, origin));
            respondJson(exchange, 200, serializeSnapshot(updated));
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request", failure.getMessage()));
        } catch (IllegalStateException | UnsupportedOperationException failure) {
            respondJson(exchange, 409, errorBody("cc_connect_error", failure.getMessage()));
        }
    }

    void handleDetach(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null) return;
        long pid = body.path("pid").asLong(0L);
        String sessionId = body.path("session_id").asText("").trim();
        if (pid <= 0 && StringUtils.isBlank(sessionId)) {
            respondJson(exchange, 400, errorBody("invalid_request", "pid or session_id is required"));
            return;
        }
        try {
            GatewayCcConnectPort.Snapshot updated = port.detach(
                new GatewayCcConnectPort.DetachRequest(pid, sessionId));
            respondJson(exchange, 200, serializeSnapshot(updated));
        } catch (IllegalArgumentException failure) {
            respondJson(exchange, 400, errorBody("invalid_request", failure.getMessage()));
        } catch (IllegalStateException | UnsupportedOperationException failure) {
            respondJson(exchange, 409, errorBody("cc_connect_error", failure.getMessage()));
        }
    }

    void handleLogs(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        long pid = parseLong(query.get("pid"), 0L);
        int tail = (int) Math.max(1, Math.min(MAX_LOG_TAIL, parseLong(query.get("tail"), DEFAULT_LOG_TAIL)));
        if (pid <= 0) {
            respondJson(exchange, 400, errorBody("invalid_request", "pid query parameter is required"));
            return;
        }
        GatewayCcConnectPort.LogsResponse logs = port.logs(pid, tail);
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("pid", logs.pid());
        ArrayNode lines = out.putArray("lines");
        for (String line : logs.lines()) {
            lines.add(line);
        }
        respondJson(exchange, 200, out);
    }

    void handleSetup(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        if (body == null) return;
        String mode = body.path("mode").asText("").trim();
        if (StringUtils.isBlank(mode)) {
            respondJson(exchange, 400, errorBody("invalid_request", "mode is required (create, bind, resume)"));
            return;
        }
        String appId = body.path("app_id").asText("").trim();
        String secretRaw = body.path("app_secret").asText("");
        String workDir = body.path("work_dir").asText("").trim();
        GatewayCcConnectPort.SetupRequest request = new GatewayCcConnectPort.SetupRequest(
            mode, appId, secretRaw.toCharArray(), workDir);

        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            Object writeLock = new Object();
            try {
                GatewayCcConnectPort.SetupResult result = port.setup(request, line -> {
                    ObjectNode event = JsonUtils.getMapper().createObjectNode();
                    event.put("type", "progress");
                    event.put("line", line == null ? "" : line);
                    writeNdjsonLine(out, writeLock, event);
                });
                ObjectNode done = JsonUtils.getMapper().createObjectNode();
                done.put("type", "result");
                done.put("ok", result.ok());
                done.put("channel", result.channel() == null ? "" : result.channel());
                done.put("message", result.message() == null ? "" : result.message());
                writeNdjsonLine(out, writeLock, done);
            } catch (RuntimeException failure) {
                ObjectNode err = JsonUtils.getMapper().createObjectNode();
                err.put("type", "result");
                err.put("ok", false);
                err.put("channel", "");
                err.put("message", failure.getMessage() == null ? "setup failed" : failure.getMessage());
                writeNdjsonLine(out, writeLock, err);
            }
        } finally {
            request.clearAppSecret();
        }
    }

    void handleCancelSetup(HttpExchange exchange) throws IOException {
        boolean cancelled = port.cancelSetup();
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("cancelled", cancelled);
        respondJson(exchange, 200, out);
    }

    private static void writeNdjsonLine(OutputStream out, Object lock, ObjectNode node) {
        synchronized (lock) {
            try {
                byte[] bytes = (JsonUtils.getMapper().writeValueAsString(node) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                out.flush();
            } catch (IOException _) {
                // Client disconnected during setup streaming.
            }
        }
    }

    private static ObjectNode serializeSnapshot(GatewayCcConnectPort.Snapshot snapshot) {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("supported", snapshot.supported());
        out.put("configured", snapshot.configured());
        out.put("setup_pending", snapshot.setupPending());
        out.put("config_path", snapshot.configPath());
        out.put("setup_in_progress", snapshot.setupInProgress());
        ArrayNode channels = out.putArray("available_channels");
        for (String channel : snapshot.availableChannels()) {
            channels.add(channel);
        }
        ArrayNode targets = out.putArray("targets");
        for (GatewayCcConnectPort.TargetStatus target : snapshot.targets()) {
            ObjectNode row = targets.addObject();
            row.put("pid", target.pid());
            row.put("work_dir", target.workDir());
            row.put("session_id", target.sessionId());
            row.put("session_title", target.sessionTitle());
            row.put("attached", target.attached());
            row.put("channel", target.channel());
            row.put("status", target.status());
            row.put("started_at", target.startedAt());
            row.put("last_error", target.lastError());
        }
        return out;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (StringUtils.isBlank(rawQuery)) return params;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(
                URLDecoder.decode(key, StandardCharsets.UTF_8),
                URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static long parseLong(String raw, long fallback) {
        if (StringUtils.isBlank(raw)) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
