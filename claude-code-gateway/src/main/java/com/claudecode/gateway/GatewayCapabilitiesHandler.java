package com.claudecode.gateway;

import static com.claudecode.gateway.GatewayRequests.readRequestJson;
import static com.claudecode.gateway.GatewayRequests.sessionIdOf;
import static com.claudecode.gateway.GatewayRequests.text;
import static com.claudecode.gateway.GatewayResponses.errorBody;
import static com.claudecode.gateway.GatewayResponses.respondJson;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * {@code GET/POST /api/session/capabilities}: progressive, cache-preserving
 * capability catalog and switches across built-in {@code Tool}s, {@code Skill}s,
 * and {@code MCP} servers/tools for the WebUI DSH Context panel.
 */
@Explanation("Exposes session-scoped and persisted default capability switches to the WebUI Context panel")
final class GatewayCapabilitiesHandler {

    private final SessionCapabilityPort capabilities;
    private final GatewaySessionContextPort sessionContext;
    private final GatewayHeadlessSessions headless;
    private final RemoteWorkerPool remoteWorkers;

    GatewayCapabilitiesHandler(
            SessionCapabilityPort capabilities,
            GatewaySessionContextPort sessionContext,
            GatewayHeadlessSessions headless,
            RemoteWorkerPool remoteWorkers) {
        this.capabilities = capabilities;
        this.sessionContext = sessionContext;
        this.headless = headless;
        this.remoteWorkers = remoteWorkers;
    }

    void handleGet(HttpExchange exchange) throws IOException {
        if (capabilities == null) {
            ObjectNode out = JsonUtils.getMapper().createObjectNode();
            out.putNull("capabilities");
            respondJson(exchange, 200, out);
            return;
        }
        String sessionId = sessionIdOf(exchange);
        bindProjectIfKnown(sessionId);
        String activeModel = resolveActiveModel(sessionId);
        SessionCapabilityPort.CapabilitiesSnapshot snap =
            capabilities.snapshot(sessionId, activeModel);
        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.set("capabilities", toJson(snap));
        respondJson(exchange, 200, out);
    }

    void handlePost(HttpExchange exchange) throws IOException {
        if (capabilities == null) {
            respondJson(exchange, 404, errorBody("not_found",
                "session capabilities controller is not configured"));
            return;
        }
        JsonNode body = readRequestJson(exchange);
        if (body == null) return;
        String sessionId = StringUtils.trimToNull(text(body, "session_id"));
        String kindRaw = StringUtils.trimToNull(text(body, "kind"));
        String name = StringUtils.trimToNull(text(body, "name"));
        String serverName = StringUtils.trimToNull(text(body, "server_name"));
        String scopeRaw = StringUtils.trimToNull(text(body, "scope"));
        JsonNode enabledNode = body.get("enabled");
        if (kindRaw == null || name == null || enabledNode == null || !enabledNode.isBoolean()) {
            respondJson(exchange, 400, errorBody("invalid_request",
                "kind, name, and boolean enabled are required"));
            return;
        }

        SessionCapabilityPort.Kind kind;
        SessionCapabilityPort.Scope scope;
        try {
            kind = SessionCapabilityPort.Kind.fromWire(kindRaw);
            scope = SessionCapabilityPort.Scope.fromWire(scopeRaw);
        } catch (IllegalArgumentException bad) {
            respondJson(exchange, 400, errorBody("invalid_request", bad.getMessage()));
            return;
        }

        bindProjectIfKnown(sessionId);
        SessionCapabilityPort.ToggleOutcome outcome;
        try {
            outcome = capabilities.toggle(new SessionCapabilityPort.ToggleRequest(
                sessionId, kind, name, serverName, enabledNode.asBoolean(), scope));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            respondJson(exchange, 400, errorBody("invalid_request", failure.getMessage()));
            return;
        }

        String activeModel = resolveActiveModel(sessionId);
        SessionCapabilityPort.CapabilitiesSnapshot refreshed = StringUtils.isNotBlank(activeModel)
            ? capabilities.snapshot(sessionId, activeModel)
            : outcome.snapshot();

        ObjectNode out = JsonUtils.getMapper().createObjectNode();
        out.put("changed", outcome.changed());
        out.put("message", outcome.message());
        out.set("capabilities", toJson(refreshed));
        respondJson(exchange, 200, out);
    }

    private void bindProjectIfKnown(String sessionId) {
        if (StringUtils.isBlank(sessionId)) return;
        for (GatewayHeadlessSessions.SessionListing listing : headless.list()) {
            if (sessionId.equals(listing.sessionId())
                    && StringUtils.isNotBlank(listing.projectPath())) {
                capabilities.bindSessionProject(sessionId, listing.projectPath());
                return;
            }
        }
        if (remoteWorkers != null) {
            remoteWorkers.workDirOf(sessionId)
                .filter(StringUtils::isNotBlank)
                .ifPresent(workDir -> capabilities.bindSessionProject(sessionId, workDir));
        }
    }

    private String resolveActiveModel(String sessionId) {
        Optional<String> local = sessionContext.selection(sessionId)
            .map(GatewaySessionContextPort.ModelSelection::current)
            .filter(StringUtils::isNotBlank);
        if (local.isPresent()) return local.get();
        if (StringUtils.isNotBlank(sessionId) && remoteWorkers != null) {
            return remoteWorkers.model(sessionId)
                .map(SessionHostModelState::current)
                .filter(StringUtils::isNotBlank)
                .orElse(null);
        }
        return null;
    }

    static ObjectNode toJson(SessionCapabilityPort.CapabilitiesSnapshot snap) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("session_id", StringUtils.defaultString(snap.sessionId()));
        node.put("model", StringUtils.defaultString(snap.model()));
        node.put("protocol", StringUtils.defaultString(snap.protocol()));
        node.put("saved_tokens", snap.savedTokens());

        ArrayNode tools = node.putArray("tools");
        for (SessionCapabilityPort.BuiltInToolItem t : snap.tools()) {
            ObjectNode item = tools.addObject();
            item.put("name", t.name());
            item.put("description", t.description());
            if (t.inputSchema() != null) item.set("input_schema", t.inputSchema());
            else item.putObject("input_schema");
            item.put("tokens", t.tokens());
            item.put("kernel", t.kernel());
            item.put("session_enabled", t.sessionEnabled());
            item.put("default_enabled", t.defaultEnabled());
            item.put("delivery_mode", t.deliveryMode());
        }

        ArrayNode skills = node.putArray("skills");
        for (SessionCapabilityPort.SkillItem s : snap.skills()) {
            ObjectNode item = skills.addObject();
            item.put("name", s.name());
            item.put("source", s.source());
            item.put("description", s.description());
            item.put("tokens", s.tokens());
            item.put("session_enabled", s.sessionEnabled());
            item.put("default_enabled", s.defaultEnabled());
            item.put("protocol_visible", s.protocolVisible());
        }

        ArrayNode mcpServers = node.putArray("mcp_servers");
        for (SessionCapabilityPort.McpServerItem srv : snap.mcpServers()) {
            ObjectNode sNode = mcpServers.addObject();
            sNode.put("name", srv.name());
            sNode.put("scope", srv.scope());
            sNode.put("transport", srv.transport());
            sNode.put("status", srv.status());
            sNode.put("instructions", srv.instructions());
            sNode.put("tokens", srv.tokens());
            sNode.put("manageable", srv.manageable());
            sNode.put("session_enabled", srv.sessionEnabled());
            sNode.put("default_enabled", srv.defaultEnabled());
            ArrayNode srvTools = sNode.putArray("tools");
            for (SessionCapabilityPort.McpToolItem mt : srv.tools()) {
                ObjectNode tNode = srvTools.addObject();
                tNode.put("server_name", mt.serverName());
                tNode.put("name", mt.name());
                tNode.put("full_name", mt.fullName());
                tNode.put("description", mt.description());
                if (mt.inputSchema() != null) tNode.set("input_schema", mt.inputSchema());
                else tNode.putObject("input_schema");
                tNode.put("tokens", mt.tokens());
                tNode.put("session_enabled", mt.sessionEnabled());
                tNode.put("default_enabled", mt.defaultEnabled());
            }
        }
        return node;
    }
}
