package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readRequestJson;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
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
            if (entry.effort() != null) task.put("effort", entry.effort());
            if (entry.workingDirectory() != null) task.put("working_directory", entry.workingDirectory());
            task.put("new_session_per_run", entry.newSessionPerRun());
            if (entry.notificationMode() != null) task.put("notification_mode", entry.notificationMode());
            task.put("archive_on_success", entry.archiveOnSuccess());
            task.put("enabled", entry.enabled());
        }
        respondJson(exchange, 200, response);
    }

    /** Handles one {@code POST} (create) exchange. */
    void handlePost(HttpExchange exchange) throws IOException {
        JsonNode request = readRequestJson(exchange);
        if (request == null) return;
        String cron = text(request, "cron");
        String prompt = text(request, "prompt");
        if (StringUtils.isBlank(cron) || StringUtils.isBlank(prompt)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "cron and prompt are required"));
            return;
        }
        boolean recurring = request.path("recurring").asBoolean(false);
        boolean durable = request.path("durable").asBoolean(false);
        GatewaySchedulePort.TaskOptions options = readOptions(request);
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
            id = schedule.add(cron, prompt, recurring, durable, options);
        } catch (RuntimeException failure) {
            respondJson(exchange, 500, errorBody("api_error",
                "failed to add task: " + failure.getMessage()));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("id", id);
        respondJson(exchange, 200, response);
    }

    /** Handles {@code PUT/PATCH /api/schedule/{id}}: updates a task or toggles enabled. */
    void handleUpdate(HttpExchange exchange, String id) throws IOException {
        if (StringUtils.isBlank(id)) {
            respondJson(exchange, 400, errorBody("invalid_request", "task id is required"));
            return;
        }
        JsonNode request = readRequestJson(exchange);
        if (request == null) return;
        String cron = text(request, "cron");
        String prompt = text(request, "prompt");
        if (StringUtils.isBlank(cron) && StringUtils.isBlank(prompt)
                && request.has("enabled") && request.get("enabled").isBoolean()) {
            boolean enabled = request.get("enabled").asBoolean();
            boolean updated = schedule.setEnabled(id, enabled);
            if (!updated) {
                respondJson(exchange, 404, errorBody("not_found", "unknown task: " + id));
                return;
            }
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("updated", true);
            response.put("id", id);
            response.put("enabled", enabled);
            respondJson(exchange, 200, response);
            return;
        }
        if (StringUtils.isBlank(cron) || StringUtils.isBlank(prompt)) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "cron and prompt are required"));
            return;
        }
        boolean recurring = request.path("recurring").asBoolean(false);
        boolean durable = request.path("durable").asBoolean(false);
        GatewaySchedulePort.TaskOptions options = readOptions(request);
        String rejection = schedule.validateUpdate(cron);
        if (rejection != null) {
            respondJson(exchange, 400, errorBody("invalid_request", rejection));
            return;
        }
        boolean updated = schedule.update(id, cron, prompt, recurring, durable, options);
        if (!updated) {
            respondJson(exchange, 404, errorBody("not_found", "unknown task: " + id));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("updated", true);
        response.put("id", id);
        respondJson(exchange, 200, response);
    }

    private static GatewaySchedulePort.TaskOptions readOptions(JsonNode request) {
        String model = text(request, "model");
        String effort = text(request, "effort");
        String workingDirectory = text(request, "working_directory");
        boolean newSessionPerRun = !request.has("new_session_per_run")
            || request.path("new_session_per_run").asBoolean(true);
        String notificationMode = text(request, "notification_mode");
        boolean archiveOnSuccess = request.path("archive_on_success").asBoolean(false);
        boolean enabled = !request.has("enabled") || request.path("enabled").asBoolean(true);
        return new GatewaySchedulePort.TaskOptions(
            model, effort, workingDirectory, newSessionPerRun,
            StringUtils.defaultIfBlank(notificationMode, "off"),
            archiveOnSuccess, enabled);
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

    /** Handles {@code POST /api/schedule/{id}/run}: triggers one task immediately. */
    void handleRun(HttpExchange exchange, String id) throws IOException {
        if (StringUtils.isBlank(id)) {
            respondJson(exchange, 400, errorBody("invalid_request", "task id is required"));
            return;
        }
        boolean triggered = schedule.runNow(id);
        if (!triggered) {
            respondJson(exchange, 404, errorBody("not_found", "unknown task: " + id));
            return;
        }
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("triggered", true);
        response.put("id", id);
        respondJson(exchange, 200, response);
    }

}
