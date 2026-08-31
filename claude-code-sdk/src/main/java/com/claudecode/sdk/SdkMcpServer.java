package com.claudecode.sdk;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.validation.JsonSchemaValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process MCP JSON-RPC server reached through SDK {@code mcp_message} control requests.
{@code createSdkMcpServer} behavior.</li></ul>
 */
public final class SdkMcpServer implements AutoCloseable {
    private final String name;
    private final String version;
    private final String instructions;
    private final boolean alwaysLoad;
    private final Map<String, SdkMcpToolDefinition> tools;
    private record PendingCall(AbortController abort, CompletableFuture<JsonNode> response) { }
    private final Map<String, PendingCall> calls = new ConcurrentHashMap<>();
    private volatile boolean closed;

    SdkMcpServer(CreateSdkMcpServerOptions options) {
        if (options == null || StringUtils.isBlank(options.name())) {
            throw new IllegalArgumentException("MCP server name is required");
        }
        name = options.name();
        version = StringUtils.defaultIfBlank(options.version(), "1.0.0");
        instructions = options.instructions();
        alwaysLoad = options.alwaysLoad();
        LinkedHashMap<String, SdkMcpToolDefinition> indexed = new LinkedHashMap<>();
        for (SdkMcpToolDefinition tool : options.tools()) {
            if (indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate MCP tool: " + tool.name());
            }
        }
        tools = Map.copyOf(indexed);
    }

    public CompletableFuture<JsonNode> handle(JsonNode message) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("MCP server is closed"));
        if (message == null || !message.isObject()) return CompletableFuture.completedFuture(null);
        String method = message.path("method").asText(null);
        if (method == null) return CompletableFuture.completedFuture(null);
        if (!message.has("id")) {
            if (Strings.CS.equals("notifications/cancelled", method)) {
                cancel(message.path("params").path("requestId"));
            }
            return CompletableFuture.completedFuture(null);
        }
        JsonNode id = message.get("id");
        return switch (method) {
            case "initialize" -> CompletableFuture.completedFuture(success(id, initializeResult()));
            case "ping" -> CompletableFuture.completedFuture(success(id, JsonUtils.getMapper().createObjectNode()));
            case "tools/list" -> CompletableFuture.completedFuture(success(id, toolsResult()));
            case "tools/call" -> call(id, message.path("params"));
            default -> CompletableFuture.completedFuture(error(id, -32601, "Method not found"));
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        result.putObject("capabilities").putObject("tools");
        result.putObject("serverInfo").put("name", name).put("version", version);
        if (instructions != null) result.put("instructions", instructions);
        return result;
    }

    private ObjectNode toolsResult() {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        ArrayNode listed = result.putArray("tools");
        tools.values().forEach(tool -> listed.add(tool.listing(alwaysLoad)));
        return result;
    }

    private CompletableFuture<JsonNode> call(JsonNode id, JsonNode params) {
        String toolName = params.path("name").asText(null);
        SdkMcpToolDefinition tool = tools.get(toolName);
        if (tool == null) return CompletableFuture.completedFuture(
            success(id, SdkMcpToolResult.error("Unknown tool: " + toolName).toJson()));
        JsonNode arguments = params.has("arguments") ? params.get("arguments")
            : JsonUtils.getMapper().createObjectNode();
        List<String> errors = JsonSchemaValidator.shared()
            .validateAgainstJsonSchema(arguments, tool.inputSchema()).errors();
        if (!errors.isEmpty()) return CompletableFuture.completedFuture(
            success(id, SdkMcpToolResult.error(String.join("; ", errors)).toJson()));
        String key = id.asText();
        AbortController abort = new AbortController();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        calls.put(key, new PendingCall(abort, response));
        Thread.startVirtualThread(() -> {
            try {
                SdkMcpToolResult result = tool.handler().call(arguments,
                    new SdkMcpToolContext(abort, params.get("_meta")));
                response.complete(success(id,
                    (result == null ? SdkMcpToolResult.text("") : result).toJson()));
            } catch (Exception failure) {
                response.complete(success(id, SdkMcpToolResult.error(
                    failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage()).toJson()));
            } finally {
                calls.remove(key);
            }
        });
        return response;
    }

    private void cancel(JsonNode requestId) {
        PendingCall call = calls.get(requestId.asText());
        if (call != null) call.abort().abort();
    }

    private static ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonUtils.getMapper().createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.putObject("error").put("code", code).put("message", message);
        return response;
    }

    @Override public void close() {
        closed = true;
        calls.values().forEach(call -> {
            call.abort().abort();
            call.response().completeExceptionally(
                new IllegalStateException("SDK MCP server was removed"));
        });
        calls.clear();
    }
}
