package com.claudecode.tools.mcp;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Tool that lists resources available from connected MCP servers.
 */
@BuiltInTool(
    name = "ListMcpResourcesTool",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class ListMcpResourcesTool extends AnnotatedTool<JsonNode, StructuredToolOutput> {

    private final McpClientRuntime clientManager;

    public ListMcpResourcesTool(McpClientRuntime clientManager) {
        this.clientManager = clientManager;
    }

    @Override
    public String description() {
        return ToolTexts.description("ListMcpResourcesTool");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("ListMcpResourcesTool");
    }

    @Override
    public String searchHint() {
        return "list resources from connected MCP servers";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode server = schema.putObject("properties").putObject("server");
        server.put("description", "Optional server name to filter resources by");
        server.put("type", "string");

        // leave extension fields accepted by the input contract.
        return schema;
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        String selected = input != null && input.has("server")
            ? input.get("server").asText("") : "";
        List<String> known = clientManager.getKnownServerIds().stream().sorted().toList();
        if (!StringUtils.isBlank(selected) && !known.contains(selected)) {
            throw new IllegalArgumentException("Server \"" + selected
                + "\" not found. Available servers: " + String.join(", ", known));
        }

        ArrayNode output = JsonUtils.getMapper().createArrayNode();
        for (String server : known) {
            if (!StringUtils.isBlank(selected) && !selected.equals(server)) continue;
            try {
                for (JsonNode resource : clientManager.listResourcesForServer(server)) {
                    output.add(canonicalResource(resource, server));
                }
            } catch (RuntimeException _) {
                // One failed server must not hide resources from healthy peers.
            }
        }
        String text = output.isEmpty()
            ? "No resources found. MCP servers may still provide tools even if they have no resources."
            : output.toString();
        return new StructuredToolOutput(text, output);
    }

    private static ObjectNode canonicalResource(JsonNode resource, String server) {
        ObjectNode copy = JsonUtils.getMapper().createObjectNode();
        copyIfPresent(resource, copy, "name");
        copyIfPresent(resource, copy, "uri");
        copyIfPresent(resource, copy, "description");
        copyIfPresent(resource, copy, "mimeType");
        copy.put("server", server);
        return copy;
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) target.set(field, value);
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }





    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("server").asText("");
    }

}
