package com.claudecode.gateway;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET/POST /api/session/context}: one session's model selection,
 * context usage, and durable session metrics, feeding the webui composer's
 * model seat, context meter, and stats pills (mirroring dsh's remote
 * session model/context surface).
 *
 * <p>The addressed session arrives as {@code ?session_id=} (GET) or a
 * {@code session_id} body field (POST); a blank id keeps the legacy
 * target — the active TUI session. The selection and mutation halves
 * ride {@link GatewaySessionContextPort}, which the CLI composition root
 * implements against the same /model catalogue and configuration the TUI
 * serves. The usage half is computed here from the port's message list
 * with the exact claude-hud status-line accounting — {@code
 * TokenEstimator.latestFinalizedUsageSnapshot} +
 * {@code contextInputTokens} over {@code ModelContextWindows} — so the
 * meter and the status line can never drift into two computations. The
 * metrics half projects the engine's durable {@code SessionMetricsSnapshot}
 * fold verbatim (raw integers; the client owns display formatting), and
 * both GET and POST attach it — a selection change must not blank the
 * stats pills.
 */
final class GatewaySessionContextHandler {

    private final GatewaySessionContextPort context;

    GatewaySessionContextHandler(GatewaySessionContextPort context) {
        this.context = context;
    }

    /** Handles one {@code GET} exchange: selection plus usage in one body. */
    void handleGet(HttpExchange exchange) throws IOException {
        respondJson(exchange, 200, body(sessionIdOf(exchange)));
    }

    /** Handles one {@code POST} exchange: select a model or an effort level. */
    void handlePost(HttpExchange exchange) throws IOException {
        String body;
        try (var input = exchange.getRequestBody()) {
            body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode request;
        try {
            request = JsonUtils.parseTree(body);
        } catch (RuntimeException _) {
            respondJson(exchange, 400, errorBody("invalid_request", "body is not valid JSON"));
            return;
        }
        String sessionId = StringUtils.trimToNull(text(request, "session_id"));
        String model = text(request, "model");
        String effort = text(request, "effort");
        if (StringUtils.isBlank(model) && StringUtils.isBlank(effort)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "either model or effort is required"));
            return;
        }
        if (StringUtils.isNotBlank(model) && StringUtils.isNotBlank(effort)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "model and effort cannot be changed in one request"));
            return;
        }
        GatewaySessionContextPort.SelectionResult result = StringUtils.isNotBlank(model)
            ? context.selectModel(sessionId, model.strip())
            : context.selectEffort(sessionId, effort.strip());
        if (!result.accepted()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                StringUtils.defaultIfBlank(result.error(), "selection was rejected")));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        selectionBody(response.putObject("selection"), result.selection());
        Optional<GatewaySessionContextPort.ContextUsage> usage = usage(sessionId);
        if (usage.isPresent()) {
            usageBody(response.putObject("context"), usage.get());
        } else {
            response.putNull("context");
        }
        attachMetrics(response, sessionId);
        respondJson(exchange, 200, response);
    }

    /** The addressed session id: {@code ?session_id=}, or null for the active TUI session. */
    private static String sessionIdOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (StringUtils.isBlank(query)) return null;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) continue;
            if (!Strings.CS.equals("session_id", pair.substring(0, split))) continue;
            return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * The addressed session's usage: the gateway computes it here from the
     * port's message list with the exact claude-hud status-line
     * accounting, so the port implementation never re-implements token
     * counting.
     */
    private Optional<GatewaySessionContextPort.ContextUsage> usage(String sessionId) {
        Optional<GatewaySessionContextPort.ModelSelection> selection =
            context.selection(sessionId);
        String model = selection.map(GatewaySessionContextPort.ModelSelection::current)
            .filter(StringUtils::isNotBlank).orElse("");
        GatewaySessionContextPort.ContextBreakdown breakdown =
            context.breakdown(sessionId).orElse(null);
        return context.messages(sessionId)
            .flatMap(messages -> usageOver(model, messages, breakdown));
    }

    private ObjectNode body(String sessionId) {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        Optional<GatewaySessionContextPort.ModelSelection> selection =
            context.selection(sessionId);
        if (selection.isPresent()) {
            selectionBody(response.putObject("selection"), selection.get());
        } else {
            response.putNull("selection");
        }
        Optional<GatewaySessionContextPort.ContextUsage> usage = usage(sessionId);
        if (usage.isPresent()) {
            usageBody(response.putObject("context"), usage.get());
        } else {
            response.putNull("context");
        }
        attachMetrics(response, sessionId);
        return response;
    }

    /**
     * Attaches the session's durable metrics: null when unavailable or
     * incomplete — a partial fold must never be served as a session total
     * ({@code docs/hud-metrics-specification.md} §6).
     */
    private void attachMetrics(ObjectNode response, String sessionId) {
        Optional<SessionMetricsSnapshot> metrics = context.metrics(sessionId)
            .filter(SessionMetricsSnapshot::complete);
        if (metrics.isEmpty()) {
            response.putNull("metrics");
            return;
        }
        metricsBody(response.putObject("metrics"), metrics.get());
    }

    /**
     * The durable whole-log fold, snake_case on the wire. Raw integers
     * only — the client owns display formatting (§4), so the gateway
     * stays formula-free.
     */
    private static void metricsBody(ObjectNode node, SessionMetricsSnapshot metrics) {
        node.put("turns", metrics.turns());
        node.put("steps", metrics.steps());
        node.put("llm_ms", metrics.llmMs());
        node.put("tool_ms", metrics.toolMs());
        node.put("ttft_ms", metrics.ttftMs());
        node.put("ttft_steps", metrics.ttftSteps());
        node.put("decode_ms", metrics.decodeMs());
        node.put("decode_tokens", metrics.decodeTokens());
        node.put("uncached_input_tokens", metrics.uncachedInputTokens());
        node.put("output_tokens", metrics.outputTokens());
        node.put("cache_write_tokens", metrics.cacheWriteTokens());
        node.put("cache_read_tokens", metrics.cacheReadTokens());
    }

    /**
     * The usage projection over one session's messages: the same finalized
     * usage anchor, input-token sum, and model-resolved context window the
     * claude-hud status line serves. Package-visible for the messages
     * snapshot handler's context enrichment.
     */
    static Optional<GatewaySessionContextPort.ContextUsage> usageOver(
            String modelId, List<Message> messages,
            GatewaySessionContextPort.ContextBreakdown breakdown) {
        if (messages == null) return Optional.empty();
        String model = StringUtils.defaultIfBlank(modelId, "");
        long contextWindow = ModelContextWindows.defaultContextWindow(model);
        if (contextWindow <= 0) return Optional.empty();
        TokenEstimator.UsageSnapshot snapshot =
            TokenEstimator.latestFinalizedUsageSnapshot(messages);
        if (snapshot == null || snapshot.usage() == null) {
            return Optional.of(new GatewaySessionContextPort.ContextUsage(
                model, contextWindow, null, null, breakdown));
        }
        String usageModel = StringUtils.isNotBlank(snapshot.model())
            ? snapshot.model() : model;
        long usedTokens = TokenEstimator.contextInputTokens(
            snapshot.usage(), usageModel);
        int used = (int) Math.round(usedTokens * 100.0 / contextWindow);
        used = Math.min(100, Math.max(0, used));
        return Optional.of(new GatewaySessionContextPort.ContextUsage(
            model, contextWindow, usedTokens, used, breakdown));
    }

    private static void selectionBody(ObjectNode node,
            GatewaySessionContextPort.ModelSelection selection) {
        node.put("current", selection.current());
        ArrayNode models = node.putArray("models");
        for (GatewaySessionContextPort.ModelChoice choice : selection.models()) {
            ObjectNode row = models.addObject();
            row.put("name", choice.name());
            row.put("label", choice.label());
            if (choice.description() != null) row.put("description", choice.description());
            row.put("default", choice.defaultOption());
        }
        if (selection.supportsEffort()) {
            ObjectNode effort = node.putObject("effort");
            effort.put("current", selection.effortCurrent());
            effort.put("effective", selection.effortEffective());
            ArrayNode choices = effort.putArray("choices");
            selection.effortChoices().forEach(choices::add);
        }
    }

    private static void usageBody(ObjectNode node,
            GatewaySessionContextPort.ContextUsage usage) {
        node.put("model", usage.model());
        node.put("context_window", usage.contextWindow());
        if (usage.measured()) {
            node.put("used_tokens", usage.usedTokens());
            node.put("used_percentage", usage.usedPercentage());
        }
        if (usage.breakdown() != null) {
            ObjectNode breakdown = node.putObject("breakdown");
            breakdown.put("system_tokens", usage.breakdown().systemTokens());
            breakdown.put("tools_tokens", usage.breakdown().toolsTokens());
            breakdown.put("message_tokens", usage.breakdown().messageTokens());
        }
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
    }

    private static void respondJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
