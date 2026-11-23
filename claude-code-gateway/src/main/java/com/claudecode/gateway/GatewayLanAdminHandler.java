package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readJsonObject;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;

/**
 * Loopback Web UI admin API for configuring the LAN Model Gateway, managing {@code sk-poco-...}
 * device keys, and inspecting recent cross-machine calls ({@code /api/lan-gateway/*}).
 */
final class GatewayLanAdminHandler {

    @FunctionalInterface
    interface ListenerRebinder {
        /** Rebinds the HTTP listener to {@code "0.0.0.0"} when {@code shareOnLan} is true, else {@code "127.0.0.1"}. */
        void apply(boolean shareOnLan) throws IOException;
    }

    @FunctionalInterface
    interface BindLockRunner {
        void run(IoAction action) throws IOException;
    }

    @FunctionalInterface
    interface IoAction {
        void execute() throws IOException;
    }

    private final LanGatewayConfigStore configStore;
    private final LanModelProxyHandler proxyHandler;
    private final GatewayLanProxyPort proxyPort;
    private final MacAwakeGuard awakeGuard;
    private final IntSupplier boundPort;
    private final Supplier<String> boundHost;
    private final ListenerRebinder rebinder;
    private final BindLockRunner bindLockRunner;

    GatewayLanAdminHandler(
            LanGatewayConfigStore configStore,
            LanModelProxyHandler proxyHandler,
            GatewayLanProxyPort proxyPort,
            MacAwakeGuard awakeGuard,
            IntSupplier boundPort,
            Supplier<String> boundHost,
            ListenerRebinder rebinder,
            BindLockRunner bindLockRunner) {
        this.configStore = configStore;
        this.proxyHandler = proxyHandler;
        this.proxyPort = proxyPort;
        this.awakeGuard = awakeGuard;
        this.boundPort = boundPort;
        this.boundHost = boundHost;
        this.rebinder = rebinder;
        this.bindLockRunner = bindLockRunner;
    }

    void handleGetStatus(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, buildStatusSnapshot(boundHost.get()));
    }

    void handleUpdateSettings(HttpExchange exchange) throws IOException {
        bindLockRunner.run(() -> {
            LanGatewayConfigStore.LanGatewayConfig previous;
            LanGatewayConfigStore.LanGatewayConfig updated;
            boolean hostChanged;
            try (exchange) {
                ObjectNode body = readJsonObject(exchange);
                if (body == null) return;
                Boolean enabled = optionalBool(body, "enabled");
                Boolean exposeDefault = optionalBool(body, "expose_default_provider");
                String standIn = body.has("tier_stand_in_model")
                    ? (body.get("tier_stand_in_model").isNull() ? "" : body.path("tier_stand_in_model").asText(""))
                    : null;
                Boolean preventSleep = optionalBool(body, "prevent_mac_sleep");
                List<String> allowedCidrs = optionalStringList(body, "allowed_cidrs");

                previous = configStore.load();
                updated = configStore.updateSettings(
                    enabled, exposeDefault, standIn, preventSleep, allowedCidrs);
                awakeGuard.sync(updated.enabled() && updated.preventMacSleep());
                hostChanged = previous.enabled() != updated.enabled();
                if (hostChanged) {
                    exchange.getResponseHeaders().set("Connection", "close");
                }
                respondJson(exchange, 200, buildStatusSnapshot(updated.enabled() ? "0.0.0.0" : "127.0.0.1"));
            }
            if (hostChanged) {
                try {
                    rebinder.apply(updated.enabled());
                } catch (IOException _) {
                    configStore.updateSettings(previous.enabled(), null, null, null, null);
                    awakeGuard.sync(previous.enabled() && previous.preventMacSleep());
                }
            }
        });
    }

    void handleCreateKey(HttpExchange exchange) throws IOException {
        ObjectNode body = readJsonObject(exchange);
        if (body == null) return;
        String name = body.path("name").asText("");
        List<String> allowedModels = optionalStringList(body, "allowed_models");
        configStore.createKey(name, allowedModels);
        respondJson(exchange, 200, buildStatusSnapshot(boundHost.get()));
    }

    void handleUpdateKey(HttpExchange exchange, String keyId) throws IOException {
        ObjectNode body = readJsonObject(exchange);
        if (body == null) return;
        String name = body.hasNonNull("name") ? body.path("name").asText("") : null;
        Boolean enabled = optionalBool(body, "enabled");
        List<String> allowedModels = optionalStringList(body, "allowed_models");
        Optional<LanGatewayConfigStore.GatewayKey> updated =
            configStore.updateKey(keyId, name, enabled, allowedModels);
        if (updated.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found", "Unknown gateway key: " + keyId));
            return;
        }
        respondJson(exchange, 200, buildStatusSnapshot(boundHost.get()));
    }

    void handleDeleteKey(HttpExchange exchange, String keyId) throws IOException {
        if (!configStore.deleteKey(keyId)) {
            respondJson(exchange, 404, errorBody("not_found", "Unknown gateway key: " + keyId));
            return;
        }
        respondJson(exchange, 200, buildStatusSnapshot(boundHost.get()));
    }

    void handleGetCalls(HttpExchange exchange) throws IOException {
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        ArrayNode arr = out.putArray("calls");
        for (LanModelProxyHandler.RecentCall c : proxyHandler.recentCallsSnapshot()) {
            ObjectNode item = arr.addObject();
            item.put("id", c.id());
            item.put("timestamp", c.timestamp());
            item.put("key_id", c.keyId());
            item.put("key_name", c.keyName());
            item.put("client_ip", c.clientIp());
            item.put("source_protocol", c.sourceProtocol());
            item.put("target_protocol", c.targetProtocol());
            item.put("requested_model", c.requestedModel());
            item.put("resolved_model", c.resolvedModel());
            item.put("streaming", c.streaming());
            item.put("status", c.status());
            item.put("ttft_ms", c.ttftMs());
            item.put("duration_ms", c.durationMs());
            item.put("input_tokens", c.inputTokens());
            item.put("output_tokens", c.outputTokens());
            if (StringUtils.isNotBlank(c.error())) item.put("error", c.error());
            else item.putNull("error");
        }
        respondJson(exchange, 200, out);
    }

    void handleClearCalls(HttpExchange exchange) throws IOException {
        proxyHandler.clearRecentCalls();
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.putArray("calls");
        respondJson(exchange, 200, out);
    }

    private ObjectNode buildStatusSnapshot(String effectiveHost) {
        LanGatewayConfigStore.LanGatewayConfig cfg = configStore.load();
        int port = boundPort.getAsInt();
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("enabled", cfg.enabled());
        out.put("listening_host", effectiveHost);
        out.put("port", port);
        out.put("expose_default_provider", cfg.exposeDefaultProvider());
        out.put("tier_stand_in_model", cfg.tierStandInModel());
        out.put("prevent_mac_sleep", cfg.preventMacSleep());
        out.put("awake_active", awakeGuard.active());
        ArrayNode cidrs = out.putArray("allowed_cidrs");
        cfg.allowedCidrs().forEach(cidrs::add);

        ArrayNode endpoints = out.putArray("endpoints");
        for (LanAddressDetector.DetectedEndpoint ep : LanAddressDetector.detect(port)) {
            ObjectNode epNode = endpoints.addObject();
            epNode.put("kind", ep.kind());
            epNode.put("host", ep.host());
            epNode.put("url", ep.url());
        }

        ArrayNode models = out.putArray("available_models");
        for (GatewayLanProxyPort.SharedModel m : proxyPort.listModels(cfg.exposeDefaultProvider())) {
            ObjectNode mn = models.addObject();
            mn.put("id", m.id());
            mn.put("protocol", m.protocol());
            mn.put("built_in", m.builtIn());
            if (m.contextWindow() != null) mn.put("context_window", m.contextWindow());
            else mn.putNull("context_window");
        }

        ArrayNode keys = out.putArray("keys");
        for (LanGatewayConfigStore.GatewayKey k : cfg.keys()) {
            ObjectNode kn = keys.addObject();
            kn.put("id", k.id());
            kn.put("name", k.name());
            kn.put("secret", k.secret());
            kn.put("masked_secret", k.maskedSecret());
            kn.put("enabled", k.enabled());
            ArrayNode allowed = kn.putArray("allowed_models");
            k.allowedModels().forEach(allowed::add);
            kn.put("created_at", k.createdAt());
            if (k.lastUsedAt() != null) kn.put("last_used_at", k.lastUsedAt());
            else kn.putNull("last_used_at");
            kn.put("total_requests", k.totalRequests());
            kn.put("input_tokens", k.inputTokens());
            kn.put("output_tokens", k.outputTokens());
        }
        return out;
    }

    private static Boolean optionalBool(ObjectNode body, String field) {
        return body.has(field) && body.get(field).isBoolean() ? body.get(field).asBoolean() : null;
    }

    private static List<String> optionalStringList(ObjectNode body, String field) {
        if (!body.has(field) || !body.get(field).isArray()) return null;
        List<String> list = new ArrayList<>();
        for (JsonNode item : body.get(field)) {
            if (item.isTextual() && StringUtils.isNotBlank(item.asText())) {
                list.add(item.asText().trim());
            }
        }
        return list;
    }
}
