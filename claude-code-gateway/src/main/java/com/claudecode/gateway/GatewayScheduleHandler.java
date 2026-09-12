package com.claudecode.gateway;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.lang3.StringUtils;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET/POST /api/schedule} and {@code DELETE /api/schedule/{id}}: lists,
 * creates, and removes cron-scheduled tasks for the scheduling panel.
 *
 * <p>Backed by {@link GatewaySchedulePort}, which the CLI composition root
 * implements against {@code CronStore}'s static, process-wide job list.
 */
final class GatewayScheduleHandler {

    private final GatewaySchedulePort schedule;

    GatewayScheduleHandler(GatewaySchedulePort schedule) {
        this.schedule = schedule;
    }

    /** Handles one {@code GET} (list) exchange. */
    void handleGet(HttpExchange exchange) throws IOException {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        ArrayNode tasks = response.putArray("tasks");
        for (GatewaySchedulePort.ScheduleEntry entry : schedule.list()) {
            ObjectNode task = tasks.addObject();
            task.put("id", entry.id());
            task.put("cron", entry.cron());
            task.put("prompt", entry.prompt());
            task.put("recurring", entry.recurring());
            task.put("durable", entry.durable());
            task.put("created_at", entry.createdAt());
            if (entry.lastFiredAt() != null) task.put("last_fired_at", entry.lastFiredAt());
            if (entry.kind() != null) task.put("kind", entry.kind());
            if (entry.agentId() != null) task.put("agent_id", entry.agentId());
            if (entry.createdBySessionId() != null) {
                task.put("created_by_session_id", entry.createdBySessionId());
            }
            if (entry.model() != null) task.put("model", entry.model());
        }
        respondJson(exchange, 200, response);
    }

    /** Handles one {@code POST} (create) exchange. */
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
        String cron = text(request, "cron");
        String prompt = text(request, "prompt");
        if (StringUtils.isBlank(cron) || StringUtils.isBlank(prompt)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "cron and prompt are required"));
            return;
        }
        boolean recurring = request.path("recurring").asBoolean(false);
        boolean durable = request.path("durable").asBoolean(false);
        String model = text(request, "model");
        // The same gate the CronCreate tool applies: a syntactically invalid
        // or never-firing expression would otherwise be stored silently and
        // never trigger, and the store capacity is bounded.
        String rejection = schedule.validateAdd(cron);
        if (rejection != null) {
            respondJson(exchange, 400, errorBody("invalid_request", rejection));
            return;
        }
        String id;
        try {
            id = schedule.add(cron, prompt, recurring, durable, model);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to add task: " + failure.getMessage()));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("id", id);
        respondJson(exchange, 200, response);
    }

    /** Handles one {@code DELETE} exchange for the task named by {@code id}. */
    void handleDelete(HttpExchange exchange, String id) throws IOException {
        if (StringUtils.isBlank(id)) {
            respondJson(exchange, 400, errorBody("invalid_request", "task id is required"));
            return;
        }
        boolean removed = schedule.remove(id);
        if (!removed) {
            respondJson(exchange, 404, errorBody("not_found", "unknown task: " + id));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("removed", true);
        respondJson(exchange, 200, response);
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
