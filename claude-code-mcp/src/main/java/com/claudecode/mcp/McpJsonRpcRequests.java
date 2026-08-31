package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds client-originated MCP JSON-RPC requests consistently across stdio, legacy SSE, and
 * streamable HTTP transports.
 */
final class McpJsonRpcRequests {

    private McpJsonRpcRequests() {}

    static Prepared prepare(AtomicInteger ids, String method, JsonNode params) {
        int id = ids.getAndIncrement();
        ObjectNode request = JsonUtils.getMapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", requestParams(method, params, id));
        }
        return new Prepared(id, request);
    }

    private static JsonNode requestParams(String method, JsonNode params, int id) {
        if (!Strings.CS.equals("tools/call", method) || !params.isObject()) {
            return params;
        }
        ObjectNode copy = ((ObjectNode) params).deepCopy();
        JsonNode existingMeta = copy.get("_meta");
        ObjectNode meta = existingMeta instanceof ObjectNode objectMeta
            ? objectMeta
            : copy.putObject("_meta");
        if (!meta.has("progressToken")) meta.put("progressToken", id);
        return copy;
    }

    record Prepared(int id, ObjectNode request) {}
}
