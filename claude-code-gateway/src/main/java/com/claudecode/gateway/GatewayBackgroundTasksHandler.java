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
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * HTTP handlers for background shell inspection/control and sub-agent
 * continuation/interruption:
 *
 * <ul>
 *   <li>{@code GET /api/shells} — list background shell tasks</li>
 *   <li>{@code GET /api/shells/{taskId}} — inspect one shell's metadata and output</li>
 *   <li>{@code POST /api/shells/{taskId}/stop} — terminate a running shell</li>
 *   <li>{@code POST /api/sessions/{sessionId}/agents/{agentId}/prompt} — continue or queue a prompt on a sub-agent</li>
 *   <li>{@code POST /api/sessions/{sessionId}/agents/{agentId}/stop} — stop a running sub-agent</li>
 * </ul>
 */
final class GatewayBackgroundTasksHandler {

    private final GatewayBackgroundTasksPort port;

    GatewayBackgroundTasksHandler(GatewayBackgroundTasksPort port) {
        this.port = port;
    }

    void handleListShells(HttpExchange exchange) throws IOException {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ArrayNode shells = body.putArray("shells");
        for (GatewayBackgroundTasksPort.ShellEntry entry : port.listShells()) {
            shells.add(encodeShellEntry(entry));
        }
        respondJson(exchange, 200, body);
    }

    void handleGetShell(HttpExchange exchange, String taskId) throws IOException {
        Optional<GatewayBackgroundTasksPort.ShellOutput> found = port.shellOutput(taskId);
        if (found.isEmpty()) {
            respondJson(exchange, 404, errorBody("not_found", "unknown shell task: " + taskId));
            return;
        }
        GatewayBackgroundTasksPort.ShellOutput detail = found.get();
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("id", detail.id());
        if (detail.command() != null) body.put("command", detail.command());
        if (detail.description() != null) body.put("description", detail.description());
        body.put("status", detail.status());
        body.put("start_time_ms", detail.startTimeMs());
        if (detail.endTimeMs() != null) body.put("end_time_ms", detail.endTimeMs());
        if (detail.exitCode() != null) body.put("exit_code", detail.exitCode());
        body.put("output", detail.output() == null ? "" : detail.output());
        body.put("truncated", detail.truncated());
        respondJson(exchange, 200, body);
    }

    void handleStopShell(HttpExchange exchange, String taskId) throws IOException {
        boolean stopped = port.stopShell(taskId);
        if (!stopped) {
            respondJson(exchange, 404, errorBody("not_found",
                "no running shell task found for id: " + taskId));
            return;
        }
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("stopped", true);
        body.put("id", taskId);
        respondJson(exchange, 200, body);
    }

    void handlePromptAgent(HttpExchange exchange, String sessionId, String agentId)
            throws IOException {
        ObjectNode payload = readJsonObject(exchange);
        if (payload == null) return;
        String prompt = extractPrompt(payload);
        if (StringUtils.isBlank(prompt)) {
            respondJson(exchange, 400, errorBody("invalid_request", "prompt is required"));
            return;
        }
        GatewayBackgroundTasksPort.AgentPromptResult result =
            port.promptAgent(sessionId, agentId, prompt);
        if (!result.ok()) {
            int status = "unavailable".equals(result.status()) ? 409 : 500;
            respondJson(exchange, status, errorBody(
                "unavailable".equals(result.status()) ? "conflict" : "api_error",
                StringUtils.defaultIfBlank(result.message(), "failed to continue sub-agent")));
            return;
        }
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("ok", true);
        body.put("status", result.status());
        body.put("agent_id", agentId);
        respondJson(exchange, 200, body);
    }

    void handleStopAgent(HttpExchange exchange, String sessionId, String agentId)
            throws IOException {
        boolean stopped = port.stopAgent(sessionId, agentId);
        if (!stopped) {
            respondJson(exchange, 404, errorBody("not_found",
                "no running sub-agent found for id: " + agentId));
            return;
        }
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("stopped", true);
        body.put("agent_id", agentId);
        respondJson(exchange, 200, body);
    }

    boolean isAgentRunning(String agentId) {
        return port.isAgentRunning(agentId);
    }

    private static String extractPrompt(ObjectNode payload) {
        JsonNode node = payload.get("prompt");
        if (node != null && node.isTextual()) return node.asText();
        JsonNode content = payload.get("content");
        if (content != null && content.isTextual()) return content.asText();
        return null;
    }

    private static ObjectNode encodeShellEntry(GatewayBackgroundTasksPort.ShellEntry entry) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("id", entry.id());
        if (entry.command() != null) node.put("command", entry.command());
        if (entry.description() != null) node.put("description", entry.description());
        node.put("status", entry.status());
        node.put("start_time_ms", entry.startTimeMs());
        if (entry.endTimeMs() != null) node.put("end_time_ms", entry.endTimeMs());
        if (entry.exitCode() != null) node.put("exit_code", entry.exitCode());
        return node;
    }
}
