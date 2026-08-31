package com.claudecode.tools.mcp;

import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpOutputStorage;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool that reads a specific resource from an MCP server.
 */
@BuiltInTool(
    name = "ReadMcpResourceTool",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class ReadMcpResourceTool extends AnnotatedTool<JsonNode, StructuredToolOutput> {

    private final McpClientRuntime clientManager;
    private final McpBinaryResourceStorage binaryStorage;

    public ReadMcpResourceTool(McpClientRuntime clientManager) {
        this(clientManager, new McpBinaryResourceStorage());
    }

    ReadMcpResourceTool(McpClientRuntime clientManager,
                        McpBinaryResourceStorage binaryStorage) {
        this.clientManager = clientManager;
        this.binaryStorage = binaryStorage;
    }

    @Override
    public String description() {
        return ToolTexts.description("ReadMcpResourceTool");
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("ReadMcpResourceTool");
    }

    @Override
    public String searchHint() {
        return "read a specific MCP resource by URI";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode props = JsonUtils.getMapper().createObjectNode();

        ObjectNode serverIdProp = JsonUtils.getMapper().createObjectNode();
        serverIdProp.put("description", "The MCP server name");
        serverIdProp.put("type", "string");
        props.set("server", serverIdProp);

        ObjectNode uriProp = JsonUtils.getMapper().createObjectNode();
        uriProp.put("description", "The resource URI to read");
        uriProp.put("type", "string");
        props.set("uri", uriProp);

        schema.set("properties", props);
        ArrayNode required = JsonUtils.getMapper().createArrayNode();
        required.add("server");
        required.add("uri");
        schema.set("required", required);

        // extension fields are therefore not rejected at schema level.
        return schema;
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        String serverId = input.has("server") ? input.get("server").asText() : "";
        String uri = input.has("uri") ? input.get("uri").asText() : "";

        var known = clientManager.getKnownServerIds().stream().sorted().toList();
        if (!known.contains(serverId)) {
            throw new IllegalArgumentException("Server \"" + serverId
                + "\" not found. Available servers: " + String.join(", ", known));
        }
        var connection = clientManager.ensureConnected(serverId);
        if (!connection.hasCapability("resources")) {
            throw new IllegalStateException(
                "Server \"" + serverId + "\" does not support resources");
        }

        JsonNode result = clientManager.readResource(serverId, uri);
        ObjectNode output = canonicalReadResult(result, serverId, context);
        return new StructuredToolOutput(output.toString(), output);
    }

    private ObjectNode canonicalReadResult(JsonNode result, String serverId,
                                           ToolExecutionContext context) {
        ObjectNode output = JsonUtils.getMapper().createObjectNode();
        ArrayNode contents = output.putArray("contents");
        JsonNode rawContents = result == null ? null : result.get("contents");
        if (rawContents == null || !rawContents.isArray()) return output;
        int index = 0;
        for (JsonNode raw : rawContents) {
            ObjectNode content = JsonUtils.getMapper().createObjectNode();
            copyIfPresent(raw, content, "uri");
            copyIfPresent(raw, content, "mimeType");
            if (raw.has("text")) {
                copyIfPresent(raw, content, "text");
            } else if (raw.path("blob").isTextual()) {
                addPersistedBlob(content, raw, serverId, index, context);
            }
            contents.add(content);
            index++;
        }
        return output;
    }

    private void addPersistedBlob(ObjectNode content, JsonNode raw, String serverId,
                                  int index, ToolExecutionContext context) {
        String mimeType = raw.path("mimeType").asText(null);
        McpOutputStorage.PersistResult persisted = binaryStorage.persist(
            raw.path("blob").asText(), mimeType, index, context);
        if (!persisted.succeeded()) {
            content.put("text", "Binary content could not be saved to disk: "
                + persisted.error());
            return;
        }
        String uri = raw.path("uri").asText();
        String filepath = persisted.filepath().toString();
        content.put("blobSavedTo", filepath);
        content.put("text", "[Resource from " + serverId + " at " + uri
            + "] Binary content (" + (mimeType != null ? mimeType : "unknown type")
            + ", " + FormatUtils.formatFileSize(persisted.size()) + ") saved to "
            + filepath);
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) target.set(field, value);
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        return input.path("server").asText("") + ": " + input.path("uri").asText("");
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }



}
