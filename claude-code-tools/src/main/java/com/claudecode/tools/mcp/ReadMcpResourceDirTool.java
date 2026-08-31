package com.claudecode.tools.mcp;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.StructuredToolOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpConnectionView;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.skills.McpSkillDiscovery;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists direct children of an MCP directory resource.
 */
@BuiltInTool(
    name = "ReadMcpResourceDirTool", aliases = {"ReadMcpResourceDir"},
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public final class ReadMcpResourceDirTool
        extends AnnotatedTool<JsonNode, StructuredToolOutput> {

    private static final String DIRECTORY_MIME_TYPE = "inode/directory";
    private static final int MAX_PAGES = 20;
    private static final int JSON_RPC_INVALID_PARAMS = -32602;

    private final McpClientRuntime clientManager;
    private final boolean mcpSkillsEnabled;

/** Default established profile: the MCP skills/directory-read gate is off. */
    public ReadMcpResourceDirTool(McpClientRuntime clientManager) {
        this(clientManager, false);
    }

    ReadMcpResourceDirTool(McpClientRuntime clientManager,
                           boolean mcpSkillsEnabled) {
        this.clientManager = clientManager;
        this.mcpSkillsEnabled = mcpSkillsEnabled;
    }
    @Override
    public String description() {
        return ToolTexts.description("ReadMcpResourceDirTool");
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode server = properties.putObject("server");
        server.put("description", "The MCP server name");
        server.put("type", "string");
        ObjectNode uri = properties.putObject("uri");
        uri.put("description", "The directory resource URI to list");
        uri.put("type", "string");
        schema.putArray("required").add("server").add("uri");
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public StructuredToolOutput call(JsonNode input, ToolExecutionContext context) {
        String server = input.path("server").asText("");
        String uri = input.path("uri").asText("");
        McpConnectionView connection = requireConnection(server);

        if (!mcpSkillsEnabled) {
            return error("Directory listing is not enabled in this build.");
        }
        if (!McpSkillDiscovery.supportsDirectoryRead(
                connection.getServerCapabilities())) {
            return error("Server \"" + connection.getServerId()
                + "\" does not support directory listing.");
        }

        ArrayNode resources = JsonUtils.getMapper().createArrayNode();
        String cursor = null;
        int page = 0;
        do {
            ObjectNode params = JsonUtils.getMapper().createObjectNode();
            params.put("uri", uri);
            if (cursor != null) params.put("cursor", cursor);

            JsonNode result;
            try {
                result = clientManager.sendRequestWithRecovery(
                    server, "resources/directory/read", params);
            } catch (RuntimeException failure) {
                if (!isInvalidParams(failure)) throw failure;
                if (page == 0) {
                    return error("Not a directory resource: " + uri
                        + ". If it is a file resource, use ReadMcpResourceTool instead.");
                }
                break;
            }

            appendResources(resources, result == null ? null : result.get("resources"));
            JsonNode nextCursor = result == null ? null : result.get("nextCursor");
            cursor = nextCursor != null && nextCursor.isTextual()
                    && !nextCursor.asText().isEmpty()
                ? nextCursor.asText() : null;
            page++;
        } while (cursor != null && page < MAX_PAGES);

        ObjectNode data = JsonUtils.getMapper().createObjectNode();
        data.set("resources", resources);
        return success(data, resources);
    }

    private McpConnectionView requireConnection(String server) {
        List<String> known = clientManager.getKnownServerIds().stream()
            .sorted().toList();
        if (!known.contains(server)) {
            throw new IllegalArgumentException("Server \"" + server
                + "\" not found. Available servers: " + String.join(", ", known));
        }
        return clientManager.ensureConnected(server);
    }

    private static void appendResources(ArrayNode output, JsonNode input) {
        if (input == null || !input.isArray()) return;
        for (JsonNode raw : input) {
            ObjectNode resource = JsonUtils.getMapper().createObjectNode();
            resource.put("uri", sanitizeUri(raw.path("uri").asText("")));
            resource.put("name", sanitizeScalar(raw.path("name").asText("")));
            JsonNode mimeType = raw.get("mimeType");
            if (mimeType != null && mimeType.isTextual()) {
                resource.put("mimeType", sanitizeScalar(mimeType.asText()));
            }
            output.add(resource);
        }
    }

    private static StructuredToolOutput error(String message) {
        ObjectNode data = JsonUtils.getMapper().createObjectNode();
        data.putArray("resources");
        data.put("error", message);
        return new StructuredToolOutput(message, data);
    }

    private static StructuredToolOutput success(ObjectNode data, ArrayNode resources) {
        String body;
        if (resources.isEmpty()) {
            body = "Directory is empty.";
        } else {
            List<String> names = new ArrayList<>(resources.size());
            for (JsonNode resource : resources) {
                String suffix = DIRECTORY_MIME_TYPE.equals(
                    resource.path("mimeType").asText(null)) ? "/" : "";
                names.add(resource.path("name").asText("") + suffix);
            }
            int count = resources.size();
            body = "Directory listing (" + count + " "
                + (count == 1 ? "entry" : "entries") + "):\n"
                + String.join("\n", names);
        }
        return new StructuredToolOutput(body + "\n\n" + data, data);
    }

    private static boolean isInvalidParams(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (StringUtils.isBlank(message)) continue;
            try {
                JsonNode error = JsonUtils.getMapper().readTree(message);
                JsonNode code = error == null ? null : error.findValue("code");
                if (code != null && code.canConvertToInt()
                        && code.asInt() == JSON_RPC_INVALID_PARAMS) {
                    return true;
                }
            } catch (Exception _) {
                // Transport errors that are not JSON-RPC error objects propagate.
            }
        }
        return false;
    }


    private static String sanitizeUri(String input) {
        String value = stripDisallowedCodePoints(input);
        for (int iteration = 0; iteration < 10; iteration++) {
            String sanitized = stripDisallowedCodePoints(value);
            if (sanitized.equals(value)) return value;
            value = sanitized;
        }
        return value;
    }


    private static String sanitizeScalar(String input) {
        String value = input;
        for (int iteration = 0; iteration < 10; iteration++) {
            String previous = value;
            value = sanitizeUri(Normalizer.normalize(value, Normalizer.Form.NFKC));
            if (value.equals(previous)) return value;
        }
        throw new IllegalArgumentException(
            "Unicode sanitization reached maximum iterations (10) for input: "
                + input.substring(0, Math.min(100, input.length())));
    }

    private static String stripDisallowedCodePoints(String input) {
        StringBuilder output = new StringBuilder(input.length());
        for (int offset = 0; offset < input.length();) {
            char first = input.charAt(offset);
            if (Character.isHighSurrogate(first)) {
                if (offset + 1 >= input.length()
                        || !Character.isLowSurrogate(input.charAt(offset + 1))) {
                    offset++;
                    continue;
                }
            } else if (Character.isLowSurrogate(first)) {
                offset++;
                continue;
            }
            int codePoint = input.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (type != Character.FORMAT
                    && type != Character.PRIVATE_USE
                    && type != Character.UNASSIGNED) {
                output.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return output.toString();
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input,
                                               ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }

}
