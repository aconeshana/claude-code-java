package com.claudecode.gateway;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.ProtocolWireCodec;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stateless {@code 4 × 4} LLM proxy plane for LAN devices (Windows / Linux / containers) and
 * local third-party tools authenticating with a {@code sk-poco-...} gateway key.
 *
 * <p>Strictly isolated from {@code SessionHost}: requests handled here never touch the host Mac's
 * TUI/headless sessions, workspace files, or local tools ({@code Bash}/{@code Write}). Callers
 * bring their own {@code messages}, {@code system}, and {@code tools}; this handler authenticates
 * the device key, translates wire protocols via {@link ProtocolWireCodec}, dispatches through
 * {@link GatewayLanProxyPort}, streams incremental SSE frames with immediate disconnect
 * cancellation, and records call telemetry in a bounded ring buffer.
 */
@Explanation("Stateless cross-machine 4x4 LLM proxy plane inspired by yetone/magpie")
final class LanModelProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(LanModelProxyHandler.class);

    static final String LOOP_GUARD_HEADER = "X-Poco-Gateway-Hop";
    private static final ScopedValue<String> OUTBOUND_HOP_ID = ScopedValue.newInstance();

    private static final int MAX_RECENT_CALLS = 100;
    private static final int AUTH_FAIL_LIMIT_PER_MINUTE = 24;
    private static final long AUTH_WINDOW_MILLIS = 60_000L;
    private static final Pattern CLAUDE_FAMILY_PATTERN = Pattern.compile(
        "^claude-(?:[0-9][0-9.-]*-)?(?:opus|sonnet|haiku|fable)(?:[-.@:]|$)",
        Pattern.CASE_INSENSITIVE);
    private static final List<String> PROXY_FORWARD_HEADERS = List.of(
        "Forwarded", "X-Forwarded-For", "X-Real-IP", "Cf-Connecting-IP",
        "True-Client-IP", "Tailscale-User-Login");

    /** Telemetry snapshot of one completed or failed LAN proxy call. */
    record RecentCall(
            long id,
            String timestamp,
            String keyId,
            String keyName,
            String clientIp,
            String sourceProtocol,
            String targetProtocol,
            String requestedModel,
            String resolvedModel,
            boolean streaming,
            int status,
            long ttftMs,
            long durationMs,
            long inputTokens,
            long outputTokens,
            String error) {}

    private record AuthFailWindow(long windowStartMs, int failures) {}

    private final LanGatewayConfigStore configStore;
    private final GatewayLanProxyPort proxyPort;
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong callSeq = new AtomicLong();
    private final Deque<RecentCall> recentCalls = new ArrayDeque<>(MAX_RECENT_CALLS);
    private final ConcurrentHashMap<String, AuthFailWindow> authFailures = new ConcurrentHashMap<>();

    LanModelProxyHandler(LanGatewayConfigStore configStore, GatewayLanProxyPort proxyPort) {
        this.configStore = configStore;
        this.proxyPort = proxyPort;
    }

    /** Returns the gateway instance ID used by {@link #LOOP_GUARD_HEADER} loop detection. */
    String instanceId() {
        return instanceId;
    }

    /** Current outbound hop ID when executing inside a proxy call on this thread, else {@code null}. */
    public static String currentOutboundHopId() {
        return OUTBOUND_HOP_ID.isBound() ? OUTBOUND_HOP_ID.get() : null;
    }

    /** True when the exchange originates directly from loopback without reverse-proxy headers. */
    static boolean isDirectLoopback(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null || !remote.getAddress().isLoopbackAddress()) {
            return false;
        }
        Headers headers = exchange.getRequestHeaders();
        for (String h : PROXY_FORWARD_HEADERS) {
            if (StringUtils.isNotBlank(headers.getFirst(h))) return false;
        }
        return true;
    }

    /** True when a loopback browser request exhibits DNS-rebinding indicators (foreign Host header). */
    static boolean isDnsRebindingAttempt(HttpExchange exchange, String localHostName) {
        Headers headers = exchange.getRequestHeaders();
        boolean fromBrowser = StringUtils.isNotBlank(headers.getFirst("Origin"))
            || StringUtils.isNotBlank(headers.getFirst("Sec-Fetch-Site"))
            || StringUtils.isNotBlank(headers.getFirst("Sec-Fetch-Mode"));
        if (!fromBrowser) return false;
        String host = headers.getFirst("Host");
        if (StringUtils.isBlank(host)) return false;
        String bareHost = stripPort(host.trim()).toLowerCase(Locale.ROOT);
        if (Strings.CS.equals("localhost", bareHost)
                || Strings.CS.equals("127.0.0.1", bareHost)
                || Strings.CS.equals("[::1]", bareHost)
                || Strings.CS.equals("::1", bareHost)
                || Strings.CS.equals("host.docker.internal", bareHost)) {
            return false;
        }
        if (StringUtils.isNotBlank(localHostName)) {
            String cleanLocal = Strings.CS.removeEnd(localHostName.trim().toLowerCase(Locale.ROOT), ".local");
            if (Strings.CS.equals(cleanLocal, bareHost)
                    || Strings.CS.equals(cleanLocal + ".local", bareHost)) {
                return false;
            }
        }
        return true;
    }

    /** True if any credential header or {@code ?key=} parameter carries a {@code sk-poco-...} key. */
    static boolean carriesGatewayKey(HttpExchange exchange) {
        for (String candidate : extractCandidateKeys(exchange)) {
            if (LanGatewayConfigStore.looksLikeGatewayKey(candidate)) return true;
        }
        return false;
    }

    /**
     * Extracts all non-empty API keys carried by {@code exchange} across {@code Authorization: Bearer},
     * {@code x-api-key}, {@code x-goog-api-key}, and {@code ?key=} so a client sending a placeholder
     * bearer alongside a real {@code x-api-key} still authenticates cleanly.
     */
    static List<String> extractCandidateKeys(HttpExchange exchange) {
        List<String> keys = new ArrayList<>(4);
        Headers headers = exchange.getRequestHeaders();
        String auth = headers.getFirst("Authorization");
        if (auth != null) {
            String trimmed = auth.trim();
            if (trimmed.length() > 7 && trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
                addUnique(keys, trimmed.substring(7).trim());
            }
        }
        addUnique(keys, headers.getFirst("x-api-key"));
        addUnique(keys, headers.getFirst("x-goog-api-key"));
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && Strings.CS.equals("key", pair.substring(0, eq))) {
                    addUnique(keys, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return keys;
    }

    List<RecentCall> recentCallsSnapshot() {
        synchronized (recentCalls) {
            return List.copyOf(recentCalls);
        }
    }

    void clearRecentCalls() {
        synchronized (recentCalls) {
            recentCalls.clear();
        }
    }

    /** Handles one inbound LAN or {@code sk-poco-...}-authenticated exchange. */
    void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        boolean directLoopback = isDirectLoopback(exchange);
        LanGatewayConfigStore.LanGatewayConfig cfg = configStore.load();

        addCorsHeaders(exchange.getResponseHeaders());
        if (Strings.CS.equals("OPTIONS", method)) {
            if (!directLoopback && !cfg.enabled()) {
                respondError(exchange, ModelApiProtocol.OPENAI_CHAT, 403, "forbidden",
                    "LAN model gateway is disabled");
                return;
            }
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
            return;
        }

        if (!directLoopback && !cfg.enabled()) {
            respondError(exchange, inferProtocolFromPath(path), 403, "forbidden",
                "LAN model gateway is not enabled on this host. Enable it in Web UI -> Settings -> 局域网网关.");
            return;
        }

        InetAddress remoteAddr = exchange.getRemoteAddress() != null
            ? exchange.getRemoteAddress().getAddress() : null;
        String clientIp = extractClientIp(exchange, remoteAddr);
        if (!directLoopback && !isIpAllowed(remoteAddr, cfg.allowedCidrs())) {
            respondError(exchange, inferProtocolFromPath(path), 403, "forbidden",
                "Client IP " + clientIp + " is not in the LAN gateway allowlist");
            return;
        }

        // Never allow LAN callers or sk-poco-... keys to touch Web UI assets or /api/* admin routes.
        if (Strings.CS.startsWith(path, "/api/") || Strings.CS.startsWith(path, "/webui/")) {
            respondError(exchange, ModelApiProtocol.OPENAI_CHAT, 403, "forbidden",
                "Host management and session APIs are restricted to loopback Web UI access");
            return;
        }

        ModelApiProtocol wireProtocol = inferProtocolFromPath(path);
        String hopHeader = exchange.getRequestHeaders().getFirst(LOOP_GUARD_HEADER);
        if (hopHeader != null && Strings.CS.contains(hopHeader, instanceId)) {
            respondError(exchange, wireProtocol, 508, "loop_detected",
                "LAN model gateway detected a self-referential routing loop (this gateway's own URL is configured as an upstream)");
            return;
        }

        if (isRateLimitedForAuthFailures(clientIp)) {
            exchange.getResponseHeaders().set("Retry-After", "60");
            respondError(exchange, wireProtocol, 429, "rate_limit_error",
                "Too many failed authentication attempts; retry after 60 seconds");
            return;
        }

        List<String> candidates = extractCandidateKeys(exchange);
        LanGatewayConfigStore.GatewayKey matchedKey = null;
        LanGatewayConfigStore.GatewayKey disabledMatch = null;
        for (String candidate : candidates) {
            Optional<LanGatewayConfigStore.GatewayKey> found = configStore.findBySecret(candidate);
            if (found.isPresent()) {
                if (found.get().enabled()) {
                    matchedKey = found.get();
                    break;
                }
                disabledMatch = found.get();
            }
        }

        if (matchedKey == null) {
            recordAuthFailure(clientIp);
            String reason = disabledMatch != null
                ? "Gateway key '" + disabledMatch.name() + "' (" + disabledMatch.maskedSecret() + ") is disabled"
                : candidates.isEmpty()
                    ? "Missing API key: send a Poco LAN gateway key (sk-poco-...) via Authorization: Bearer <key>, x-api-key, or x-goog-api-key"
                    : "Invalid Poco LAN gateway key";
            respondError(exchange, wireProtocol, 401, "authentication_error", reason);
            return;
        }
        clearAuthFailures(clientIp);

        boolean get = Strings.CS.equals("GET", method);
        boolean post = Strings.CS.equals("POST", method);

        if (get && (Strings.CS.equals("/", path) || Strings.CS.equals("/v1", path)
                || Strings.CS.equals("/v1/", path))) {
            ObjectNode info = JsonUtils.getMapper().createObjectNode();
            info.put("name", "poco-lan-gateway");
            info.put("status", "ok");
            info.put("key_name", matchedKey.name());
            respondRawJson(exchange, 200, info.toString());
            return;
        }

        if (get && (Strings.CS.equals("/v1/models", path) || Strings.CS.equals("/models", path))) {
            handleListOpenAiModels(exchange, cfg, matchedKey);
            return;
        }
        if (get && Strings.CS.startsWith(path, "/v1/models/")) {
            String modelId = URLDecoder.decode(
                path.substring("/v1/models/".length()), StandardCharsets.UTF_8);
            handleGetSingleOpenAiModel(exchange, cfg, matchedKey, modelId);
            return;
        }
        if (get && (Strings.CS.equals("/v1beta/models", path)
                || Strings.CS.equals("/v1beta/models/", path))) {
            handleListGeminiModels(exchange, cfg, matchedKey);
            return;
        }

        if (post && (Strings.CS.equals("/v1/messages/count_tokens", path)
                || Strings.CS.equals("/messages/count_tokens", path))) {
            handleCountTokens(exchange, cfg, matchedKey, ModelApiProtocol.ANTHROPIC, null);
            return;
        }
        if (post && (Strings.CS.equals("/v1/messages", path) || Strings.CS.equals("/messages", path))) {
            handleInference(exchange, cfg, matchedKey, clientIp, ModelApiProtocol.ANTHROPIC, null, null);
            return;
        }
        if (post && (Strings.CS.equals("/v1/chat/completions", path)
                || Strings.CS.equals("/chat/completions", path))) {
            handleInference(exchange, cfg, matchedKey, clientIp, ModelApiProtocol.OPENAI_CHAT, null, null);
            return;
        }
        if (post && (Strings.CS.equals("/v1/responses", path)
                || Strings.CS.equals("/responses", path))) {
            handleInference(exchange, cfg, matchedKey, clientIp, ModelApiProtocol.OPENAI_RESPONSES, null, null);
            return;
        }
        if (post && Strings.CS.startsWith(path, "/v1beta/models/")) {
            String tail = path.substring("/v1beta/models/".length());
            int colon = tail.lastIndexOf(':');
            if (colon > 0) {
                String geminiModel = URLDecoder.decode(tail.substring(0, colon), StandardCharsets.UTF_8);
                String action = tail.substring(colon + 1);
                switch (action) {
                    case "generateContent" -> {
                        handleInference(exchange, cfg, matchedKey, clientIp,
                            ModelApiProtocol.GEMINI, geminiModel, Boolean.FALSE);
                        return;
                    }
                    case "streamGenerateContent" -> {
                        handleInference(exchange, cfg, matchedKey, clientIp,
                            ModelApiProtocol.GEMINI, geminiModel, Boolean.TRUE);
                        return;
                    }
                    case "countTokens" -> {
                        handleCountTokens(exchange, cfg, matchedKey, ModelApiProtocol.GEMINI, geminiModel);
                        return;
                    }
                    default -> { }
                }
            }
        }

        respondError(exchange, wireProtocol, 404, "not_found_error",
            "Unsupported LAN model gateway route: " + method + " " + path);
    }

    private void handleListOpenAiModels(
            HttpExchange exchange,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key) throws IOException {
        List<GatewayLanProxyPort.SharedModel> shared = proxyPort.listModels(cfg.exposeDefaultProvider());
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("object", "list");
        ArrayNode data = body.putArray("data");
        long nowSec = Instant.now().getEpochSecond();
        for (GatewayLanProxyPort.SharedModel m : shared) {
            if (!key.allowsModel(m.id())) continue;
            ObjectNode item = data.addObject();
            item.put("id", m.id());
            item.put("display_name", m.id());
            item.put("object", "model");
            item.put("created", nowSec);
            item.put("owned_by", m.builtIn() ? "host-default" : m.protocol());
            if (m.contextWindow() != null) {
                item.put("context_window", m.contextWindow());
            }
        }
        respondRawJson(exchange, 200, body.toString());
    }

    private void handleGetSingleOpenAiModel(
            HttpExchange exchange,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key,
            String modelId) throws IOException {
        if (!key.allowsModel(modelId)) {
            respondError(exchange, ModelApiProtocol.OPENAI_CHAT, 403, "permission_error",
                "Gateway key '" + key.name() + "' is not permitted to use model '" + modelId + "'");
            return;
        }
        Optional<GatewayLanProxyPort.SharedModel> found = proxyPort.listModels(cfg.exposeDefaultProvider())
            .stream()
            .filter(m -> Strings.CS.equals(m.id(), modelId))
            .findFirst();
        if (found.isEmpty()) {
            respondError(exchange, ModelApiProtocol.OPENAI_CHAT, 404, "not_found_error",
                "Model '" + modelId + "' is not configured on this gateway");
            return;
        }
        GatewayLanProxyPort.SharedModel m = found.get();
        ObjectNode item = JsonUtils.getMapper().createObjectNode();
        item.put("id", m.id());
        item.put("display_name", m.id());
        item.put("object", "model");
        item.put("created", Instant.now().getEpochSecond());
        item.put("owned_by", m.builtIn() ? "host-default" : m.protocol());
        respondRawJson(exchange, 200, item.toString());
    }

    private void handleListGeminiModels(
            HttpExchange exchange,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key) throws IOException {
        List<GatewayLanProxyPort.SharedModel> shared = proxyPort.listModels(cfg.exposeDefaultProvider());
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ArrayNode models = body.putArray("models");
        for (GatewayLanProxyPort.SharedModel m : shared) {
            if (!key.allowsModel(m.id())) continue;
            ObjectNode item = models.addObject();
            item.put("name", "models/" + m.id());
            item.put("displayName", m.id());
            ArrayNode methods = item.putArray("supportedGenerationMethods");
            methods.add("generateContent");
            methods.add("streamGenerateContent");
            methods.add("countTokens");
            if (m.contextWindow() != null) {
                item.put("inputTokenLimit", m.contextWindow());
            }
        }
        respondRawJson(exchange, 200, body.toString());
    }

    private void handleCountTokens(
            HttpExchange exchange,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key,
            ModelApiProtocol protocol,
            String pathModel) throws IOException {
        String rawBody = readUtf8Body(exchange);
        CreateMessageRequest parsed;
        try {
            parsed = parseRequestWithOptionalPathModel(protocol, rawBody, pathModel);
        } catch (ApiException invalid) {
            respondError(exchange, protocol, 400, "invalid_request_error", invalid.getMessage());
            return;
        }
        String requestedModel = StringUtils.defaultIfBlank(parsed.model(), pathModel);
        ResolvedRoute route = resolveTargetModel(requestedModel, cfg, key);
        if (route.errorMessage != null) {
            respondError(exchange, protocol, route.errorStatus, "invalid_request_error", route.errorMessage);
            return;
        }
        long tokens;
        try {
            tokens = ScopedValue.where(OUTBOUND_HOP_ID, instanceId).call(() ->
                route.client.countTokens(route.resolvedModel, parsed.messages(), parsed.tools()));
            if (tokens < 0) {
                tokens = estimateTokensHeuristic(rawBody);
            }
        } catch (Exception _) {
            tokens = estimateTokensHeuristic(rawBody);
        }
        respondRawJson(exchange, 200, ProtocolWireCodec.encodeCountTokensResponse(protocol, tokens));
    }

    private void handleInference(
            HttpExchange exchange,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key,
            String clientIp,
            ModelApiProtocol clientProtocol,
            String pathModel,
            Boolean forceStreamOverride) throws IOException {
        long startNanos = System.nanoTime();
        String rawBody = readUtf8Body(exchange);
        CreateMessageRequest parsed;
        try {
            parsed = parseRequestWithOptionalPathModel(clientProtocol, rawBody, pathModel);
        } catch (ApiException invalid) {
            respondError(exchange, clientProtocol, 400, "invalid_request_error", invalid.getMessage());
            return;
        }

        String requestedModel = StringUtils.defaultIfBlank(parsed.model(), pathModel);
        if (StringUtils.isBlank(requestedModel)) {
            respondError(exchange, clientProtocol, 400, "invalid_request_error",
                "model is required");
            return;
        }

        boolean streaming = forceStreamOverride != null ? forceStreamOverride : parsed.stream();
        ResolvedRoute route = resolveTargetModel(requestedModel, cfg, key);
        if (route.errorMessage != null) {
            recordCall(key, clientIp, clientProtocol, null, requestedModel, requestedModel,
                streaming, route.errorStatus, 0L, elapsedMs(startNanos), 0L, 0L, route.errorMessage);
            respondError(exchange, clientProtocol, route.errorStatus,
                route.errorStatus == 403 ? "permission_error" : "not_found_error",
                route.errorMessage);
            return;
        }

        ModelApiProtocol targetProtocol = proxyPort.upstreamProtocol(route.resolvedModel)
            .orElse(ModelApiProtocol.ANTHROPIC);
        CreateMessageRequest withTarget = parsed.withModelAndStream(route.resolvedModel, streaming);
        CreateMessageRequest outbound = targetProtocol == ModelApiProtocol.ANTHROPIC
            ? ProtocolWireCodec.adaptForAnthropicUpstream(withTarget)
            : withTarget;

        if (!streaming) {
            handleUnaryInference(exchange, key, clientIp, clientProtocol, targetProtocol,
                requestedModel, route, outbound, startNanos);
        } else {
            boolean geminiJsonArrayStream = clientProtocol == ModelApiProtocol.GEMINI
                && !queryHasAltSse(exchange.getRequestURI().getRawQuery());
            handleStreamingInference(exchange, key, clientIp, clientProtocol, targetProtocol,
                requestedModel, route, outbound, geminiJsonArrayStream, startNanos);
        }
    }

    private void handleUnaryInference(
            HttpExchange exchange,
            LanGatewayConfigStore.GatewayKey key,
            String clientIp,
            ModelApiProtocol clientProtocol,
            ModelApiProtocol targetProtocol,
            String requestedModel,
            ResolvedRoute route,
            CreateMessageRequest outbound,
            long startNanos) throws IOException {
        try {
            ApiMessage reply = ScopedValue.where(OUTBOUND_HOP_ID, instanceId).call(
                () -> route.client.createMessage(outbound));
            Usage u = reply.usage() == null ? Usage.EMPTY : reply.usage();
            long inTok = u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens();
            long outTok = u.outputTokens();
            long durMs = elapsedMs(startNanos);
            configStore.recordUsage(key.id(), inTok, outTok);
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, false, 200, durMs, durMs, inTok, outTok, null);
            String encoded = ProtocolWireCodec.encodeResponse(clientProtocol, reply);
            respondRawJson(exchange, 200, encoded);
        } catch (ApiException apiErr) {
            int status = normalizeUpstreamStatus(apiErr.statusCode());
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, false, status, 0L, elapsedMs(startNanos), 0L, 0L,
                apiErr.getMessage());
            respondError(exchange, clientProtocol, status, "api_error", apiErr.getMessage());
        } catch (Exception failure) {
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, false, 502, 0L, elapsedMs(startNanos), 0L, 0L,
                failure.toString());
            respondError(exchange, clientProtocol, 502, "api_error",
                StringUtils.defaultIfBlank(failure.getMessage(), "Upstream LLM request failed"));
        }
    }

    private void handleStreamingInference(
            HttpExchange exchange,
            LanGatewayConfigStore.GatewayKey key,
            String clientIp,
            ModelApiProtocol clientProtocol,
            ModelApiProtocol targetProtocol,
            String requestedModel,
            ResolvedRoute route,
            CreateMessageRequest outbound,
            boolean geminiJsonArrayStream,
            long startNanos) throws IOException {
        Iterator<StreamEvent> stream;
        try {
            stream = ScopedValue.where(OUTBOUND_HOP_ID, instanceId).call(
                () -> route.client.createMessageStream(outbound));
        } catch (ApiException apiErr) {
            int status = normalizeUpstreamStatus(apiErr.statusCode());
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, true, status, 0L, elapsedMs(startNanos), 0L, 0L,
                apiErr.getMessage());
            respondError(exchange, clientProtocol, status, "api_error", apiErr.getMessage());
            return;
        } catch (Exception failure) {
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, true, 502, 0L, elapsedMs(startNanos), 0L, 0L,
                failure.toString());
            respondError(exchange, clientProtocol, 502, "api_error",
                StringUtils.defaultIfBlank(failure.getMessage(), "Failed to open upstream stream"));
            return;
        }

        ProtocolWireCodec.StreamProjector projector =
            ProtocolWireCodec.newStreamProjector(clientProtocol, route.resolvedModel);
        long ttftMs = 0L;
        String streamError = null;
        boolean clientDisconnected = false;

        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type",
                geminiJsonArrayStream ? "application/json; charset=utf-8" : "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream out = exchange.getResponseBody()) {
                boolean firstJsonArrayChunk = true;
                if (geminiJsonArrayStream) {
                    out.write('[');
                    out.flush();
                }

                while (stream.hasNext()) {
                    StreamEvent event = stream.next();
                    if (event instanceof StreamEvent.Error(ApiException err, _)) {
                        streamError = err != null ? err.getMessage() : "upstream stream error";
                        if (!geminiJsonArrayStream) {
                            String errFrame = formatSseErrorFrame(clientProtocol, streamError);
                            out.write(errFrame.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        break;
                    }
                    List<String> frames = projector.onEvent(event);
                    if (ttftMs == 0L && projector.emittedContent()) {
                        ttftMs = Math.max(1L, elapsedMs(startNanos));
                    }
                    for (String frame : frames) {
                        firstJsonArrayChunk = writeFrame(out, frame, geminiJsonArrayStream, firstJsonArrayChunk);
                    }
                }

                if (streamError == null) {
                    for (String closingFrame : projector.finish()) {
                        firstJsonArrayChunk = writeFrame(out, closingFrame, geminiJsonArrayStream, firstJsonArrayChunk);
                    }
                }
                if (geminiJsonArrayStream) {
                    out.write(']');
                    out.flush();
                }
            } catch (IOException _) {
                // Downstream LAN client closed connection (e.g. Ctrl+C); abort upstream immediately.
                clientDisconnected = true;
            }
        } finally {
            if (stream instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception _) { }
            }
            Usage u = projector.usage();
            long inTok = u.inputTokens() + u.cacheReadInputTokens() + u.cacheCreationInputTokens();
            long outTok = u.outputTokens();
            if (inTok > 0 || outTok > 0) {
                configStore.recordUsage(key.id(), inTok, outTok);
            }
            int recordedStatus = streamError != null ? 502 : (clientDisconnected ? 499 : 200);
            String errNote = clientDisconnected ? "client disconnected" : streamError;
            recordCall(key, clientIp, clientProtocol, targetProtocol, requestedModel,
                route.resolvedModel, true, recordedStatus, ttftMs, elapsedMs(startNanos),
                inTok, outTok, errNote);
        }
    }

    private static boolean writeFrame(
            OutputStream out, String sseFrame, boolean geminiJsonArray, boolean firstArrayElem)
            throws IOException {
        if (!geminiJsonArray) {
            out.write(sseFrame.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return firstArrayElem;
        }
        String trimmed = sseFrame.trim();
        if (!Strings.CS.startsWith(trimmed, "data:")) return firstArrayElem;
        String jsonPayload = trimmed.substring("data:".length()).trim();
        if (jsonPayload.isEmpty() || Strings.CS.equals("[DONE]", jsonPayload)) return firstArrayElem;
        if (!firstArrayElem) {
            out.write(',');
            out.write('\n');
        }
        out.write(jsonPayload.getBytes(StandardCharsets.UTF_8));
        out.flush();
        return false;
    }

    private record ResolvedRoute(
            LlmClient client, String resolvedModel, int errorStatus, String errorMessage) {}

    private ResolvedRoute resolveTargetModel(
            String requestedModel,
            LanGatewayConfigStore.LanGatewayConfig cfg,
            LanGatewayConfigStore.GatewayKey key) {
        String cleanRequested = Strings.CS.removeEnd(requestedModel.trim(), "[1m]");
        if (!key.allowsModel(cleanRequested)) {
            return new ResolvedRoute(null, cleanRequested, 403,
                "Gateway key '" + key.name() + "' is not authorized to access model '" + cleanRequested + "'");
        }
        Optional<LlmClient> direct = proxyPort.resolveClient(cleanRequested, cfg.exposeDefaultProvider());
        if (direct.isPresent()) {
            return new ResolvedRoute(direct.get(), cleanRequested, 200, null);
        }
        // Claude Code tier stand-in (magpie claudeTierStandIn): map unserved claude-*-haiku/sonnet/opus
        // background helper calls onto the user's configured stand-in model if allowed.
        String standIn = cfg.tierStandInModel();
        if (StringUtils.isNotBlank(standIn)
                && CLAUDE_FAMILY_PATTERN.matcher(cleanRequested).find()
                && key.allowsModel(standIn)) {
            Optional<LlmClient> fallback = proxyPort.resolveClient(standIn, cfg.exposeDefaultProvider());
            if (fallback.isPresent()) {
                log.debug("Mapped unserved Claude tier model '{}' -> stand-in '{}'", cleanRequested, standIn);
                return new ResolvedRoute(fallback.get(), standIn, 200, null);
            }
        }
        return new ResolvedRoute(null, cleanRequested, 404,
            "Model '" + cleanRequested + "' is not configured on this Poco LAN gateway (check ~/.claude/model.json or enable default provider sharing)");
    }

    private static CreateMessageRequest parseRequestWithOptionalPathModel(
            ModelApiProtocol protocol, String rawBody, String pathModel) {
        String effectiveJson = StringUtils.isBlank(rawBody) ? "{}" : rawBody;
        if (StringUtils.isNotBlank(pathModel)) {
            try {
                JsonNode parsed = JsonUtils.getMapper().readTree(effectiveJson);
                if (parsed != null && parsed.isObject() && !parsed.hasNonNull("model")) {
                    ((ObjectNode) parsed).put("model", pathModel);
                    effectiveJson = parsed.toString();
                }
            } catch (Exception _) {
                // Let ProtocolWireCodec surface malformed JSON consistently.
            }
        }
        return ProtocolWireCodec.parseRequest(protocol, effectiveJson);
    }

    private void recordCall(
            LanGatewayConfigStore.GatewayKey key,
            String clientIp,
            ModelApiProtocol source,
            ModelApiProtocol target,
            String requestedModel,
            String resolvedModel,
            boolean streaming,
            int status,
            long ttftMs,
            long durationMs,
            long inputTokens,
            long outputTokens,
            String error) {
        RecentCall entry = new RecentCall(
            callSeq.incrementAndGet(),
            Instant.now().toString(),
            key != null ? key.id() : "",
            key != null ? key.name() : "",
            clientIp,
            source != null ? source.configValue() : "chat",
            target != null ? target.configValue() : "",
            StringUtils.defaultString(requestedModel),
            StringUtils.defaultString(resolvedModel),
            streaming,
            status,
            ttftMs,
            durationMs,
            inputTokens,
            outputTokens,
            error);
        synchronized (recentCalls) {
            recentCalls.addFirst(entry);
            while (recentCalls.size() > MAX_RECENT_CALLS) {
                recentCalls.removeLast();
            }
        }
    }

    private boolean isRateLimitedForAuthFailures(String clientIp) {
        if (StringUtils.isBlank(clientIp)) return false;
        AuthFailWindow win = authFailures.get(clientIp);
        if (win == null) return false;
        long now = System.currentTimeMillis();
        if (now - win.windowStartMs() > AUTH_WINDOW_MILLIS) {
            authFailures.remove(clientIp, win);
            return false;
        }
        return win.failures() >= AUTH_FAIL_LIMIT_PER_MINUTE;
    }

    private void recordAuthFailure(String clientIp) {
        if (StringUtils.isBlank(clientIp)) return;
        long now = System.currentTimeMillis();
        authFailures.compute(clientIp, (_, existing) -> {
            if (existing == null || now - existing.windowStartMs() > AUTH_WINDOW_MILLIS) {
                return new AuthFailWindow(now, 1);
            }
            return new AuthFailWindow(existing.windowStartMs(), existing.failures() + 1);
        });
    }

    private void clearAuthFailures(String clientIp) {
        if (StringUtils.isNotBlank(clientIp)) {
            authFailures.remove(clientIp);
        }
    }

    static boolean isIpAllowed(InetAddress addr, List<String> allowedCidrs) {
        if (allowedCidrs == null || allowedCidrs.isEmpty()) return true;
        if (addr == null) return false;
        if (addr.isLoopbackAddress()) return true;
        byte[] targetBytes = addr.getAddress();
        for (String rule : allowedCidrs) {
            if (matchesCidr(targetBytes, rule)) return true;
        }
        return false;
    }

    private static boolean matchesCidr(byte[] addrBytes, String rule) {
        if (StringUtils.isBlank(rule)) return false;
        String trimmed = rule.trim();
        if (Strings.CS.equals("*", trimmed) || Strings.CS.equals("0.0.0.0/0", trimmed)) return true;
        try {
            int slash = trimmed.indexOf('/');
            String ipPart = slash > 0 ? trimmed.substring(0, slash) : trimmed;
            InetAddress ruleAddr = InetAddress.getByName(ipPart);
            if (!(ruleAddr instanceof Inet4Address) || addrBytes.length != 4) {
                return MessageDigestEquals(addrBytes, ruleAddr.getAddress());
            }
            byte[] ruleBytes = ruleAddr.getAddress();
            int prefixLen = slash > 0 ? Integer.parseInt(trimmed.substring(slash + 1)) : 32;
            if (prefixLen < 0 || prefixLen > 32) return false;
            int addrInt = toIpv4Int(addrBytes);
            int ruleInt = toIpv4Int(ruleBytes);
            int mask = prefixLen == 0 ? 0 : (-1 << (32 - prefixLen));
            return (addrInt & mask) == (ruleInt & mask);
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean MessageDigestEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static int toIpv4Int(byte[] b) {
        return ((b[0] & 0xFF) << 24)
            | ((b[1] & 0xFF) << 16)
            | ((b[2] & 0xFF) << 8)
            | (b[3] & 0xFF);
    }

    private static ModelApiProtocol inferProtocolFromPath(String path) {
        if (path == null) return ModelApiProtocol.OPENAI_CHAT;
        if (Strings.CS.startsWith(path, "/v1beta/")) return ModelApiProtocol.GEMINI;
        if (Strings.CS.startsWith(path, "/v1/messages") || Strings.CS.startsWith(path, "/messages")) {
            return ModelApiProtocol.ANTHROPIC;
        }
        if (Strings.CS.startsWith(path, "/v1/responses") || Strings.CS.startsWith(path, "/responses")) {
            return ModelApiProtocol.OPENAI_RESPONSES;
        }
        return ModelApiProtocol.OPENAI_CHAT;
    }

    private static void respondError(
            HttpExchange exchange,
            ModelApiProtocol protocol,
            int status,
            String code,
            String message) throws IOException {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        if (protocol == ModelApiProtocol.ANTHROPIC) {
            root.put("type", "error");
            root.putObject("error").put("type", code).put("message", message);
        } else if (protocol == ModelApiProtocol.GEMINI) {
            ObjectNode err = root.putObject("error");
            err.put("code", status);
            err.put("message", message);
            err.put("status", status == 401 ? "UNAUTHENTICATED"
                : status == 403 ? "PERMISSION_DENIED"
                : status == 404 ? "NOT_FOUND"
                : status == 429 ? "RESOURCE_EXHAUSTED" : "INVALID_ARGUMENT");
        } else {
            ObjectNode err = root.putObject("error");
            err.put("message", message);
            err.put("type", code);
            err.put("code", code);
        }
        respondRawJson(exchange, status, root.toString());
    }

    private static String formatSseErrorFrame(ModelApiProtocol protocol, String message) {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.putObject("error").put("type", "api_error").put("message", message);
        return switch (protocol) {
            case ANTHROPIC -> "event: error\ndata: " + root + "\n\n";
            case OPENAI_RESPONSES -> {
                ObjectNode ev = JsonUtils.getMapper().createObjectNode();
                ev.put("type", "error");
                ev.put("message", message);
                yield "event: error\ndata: " + ev + "\n\n";
            }
            default -> "data: " + root + "\n\n";
        };
    }

    private static void respondRawJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers",
            "Authorization, Content-Type, x-api-key, x-goog-api-key, anthropic-version, anthropic-beta, OpenAI-Beta");
    }

    private static String readUtf8Body(HttpExchange exchange) throws IOException {
        try (var in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean queryHasAltSse(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) return false;
        for (String pair : rawQuery.split("&")) {
            if (Strings.CI.equals("alt=sse", pair.trim())) return true;
        }
        return false;
    }

    private static int normalizeUpstreamStatus(int code) {
        return (code >= 400 && code <= 599) ? code : 502;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static long estimateTokensHeuristic(String body) {
        if (StringUtils.isEmpty(body)) return 1L;
        return Math.max(1L, body.getBytes(StandardCharsets.UTF_8).length / 4L);
    }

    private static String extractClientIp(HttpExchange exchange, InetAddress remoteAddr) {
        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (StringUtils.isNotBlank(xff)) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return remoteAddr != null ? remoteAddr.getHostAddress() : "unknown";
    }

    private static String stripPort(String hostPort) {
        if (Strings.CS.startsWith(hostPort, "[")) {
            int close = hostPort.indexOf(']');
            return close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        }
        int colon = hostPort.lastIndexOf(':');
        return colon > 0 ? hostPort.substring(0, colon) : hostPort;
    }

    private static void addUnique(List<String> list, String value) {
        if (StringUtils.isBlank(value)) return;
        String trimmed = value.trim();
        if (!list.contains(trimmed)) list.add(trimmed);
    }
}
