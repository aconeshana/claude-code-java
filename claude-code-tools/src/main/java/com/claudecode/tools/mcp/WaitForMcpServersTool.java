package com.claudecode.tools.mcp;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Model-visible wait gate for MCP servers whose startup is still pending.
 */
@BuiltInTool(
    name = WaitForMcpServersTool.NAME,
    readOnly = true,
    concurrencySafe = true,
    maxResultSizeChars = 10_000
)
public final class WaitForMcpServersTool extends AnnotatedTool<JsonNode, ToolResult> {

    static final String NAME = "WaitForMcpServers";

    /** Connection-state facade kept small so the exact model contract is unit-testable. */
    interface Controller {
        boolean hasPendingServers();
        WaitResult waitForServers(List<String> servers);
    }

    public record WaitResult(
        boolean ready,
        List<String> connected,
        List<String> failed,
        List<String> stillPending,
        List<String> needsAuth,
        List<String> disabled,
        List<String> unknown
    ) {
        public WaitResult {
            connected = List.copyOf(connected);
            failed = List.copyOf(failed);
            stillPending = List.copyOf(stillPending);
            needsAuth = List.copyOf(needsAuth);
            disabled = List.copyOf(disabled);
            unknown = List.copyOf(unknown);
        }
    }

    private final Controller controller;

    WaitForMcpServersTool(Controller controller) {
        this.controller = controller;
    }

    @Override
    public String description() {
        return ToolTexts.description("WaitForMcpServers");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode servers = schema.putObject("properties").putObject("servers");
        servers.put("description", "Server names to wait for (default: all pending)");
        servers.put("type", "array");
        servers.putObject("items").put("type", "string");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult call(JsonNode input, ToolExecutionContext context) {
        List<String> requested = new ArrayList<>();
        JsonNode servers = input == null ? null : input.get("servers");
        if (servers != null && servers.isArray()) {
            for (JsonNode server : servers) requested.add(server.asText());
        }

        WaitResult result = controller.waitForServers(List.copyOf(requested));
        List<String> lines = new ArrayList<>();
        lines.add("ready: " + result.ready());
        append(lines, result.connected(),
            "Connected (their tools are now available — call them directly): ");
        append(lines, result.failed(), "Failed to connect: ");
        append(lines, result.stillPending(), "Still connecting (try again or proceed without): ");
        append(lines, result.needsAuth(), "Needs authentication (ask the user to run /mcp): ");
        append(lines, result.disabled(), "Disabled (ask the user to enable via /mcp): ");
        append(lines, result.unknown(), "Unknown (no MCP server with this name is configured): ");

        return new ToolResult(
            List.of(new TextBlock(String.join("\n", lines))),
            !result.ready(),
            null,
            result).withExplicitIsErrorField();
    }

    private static void append(List<String> lines, List<String> names, String prefix) {
        if (!names.isEmpty()) lines.add(prefix + String.join(", ", names));
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }




    @Override
    public boolean isEnabled() {
        return controller.hasPendingServers();
    }
}
