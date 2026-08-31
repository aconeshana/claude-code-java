package com.claudecode.mcp;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Classifies an inbound MCP JSON-RPC message and dispatches it to the right handler table.
 */
final class McpMessageDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(McpMessageDispatcher.class);

    private McpMessageDispatcher() {}

    /**
     * @param msg              parsed JSON-RPC message
     * @param pending          in-flight request futures keyed by outbound id
     * @param requestHandlers  method → handler for server-initiated requests
     * @param notificationHandlers method → handler for server-initiated notifications
     * @param replySender      callback used to send a JSON-RPC reply for an
     *                         inbound request. Receives the fully-formed reply
     *                         object ({@code {jsonrpc, id, result|error}}).
     */
    static void dispatch(JsonNode msg,
                          Map<Integer, CompletableFuture<JsonNode>> pending,
                          Map<String, ServerRequestHandler> requestHandlers,
                          Map<String, NotificationHandler> notificationHandlers,
                          Consumer<ObjectNode> replySender) {
        if (msg == null || !msg.isObject()) return;

        boolean hasId = msg.has("id") && !msg.get("id").isNull();
        boolean hasMethod = msg.has("method") && msg.get("method").isTextual();

        if (hasId && !hasMethod) {
            // Response to a client-issued request.
            dispatchResponse(msg, pending);
            return;
        }
        if (hasId) {
            // Server-initiated request; we owe a reply. Reaching here means the
            // previous branch failed, so hasMethod must be true (id present and
            // method absent was already handled above).
            dispatchServerRequest(msg, requestHandlers, replySender);
            return;
        }
        if (hasMethod) {
            // Notification — no reply, no id. hasId is false here; hasMethod may
            // be false (a message with neither id nor method, case ④), in which
            // case it falls through to the ignore-log instead of NPE-ing on
// msg.get("method").asText in dispatchNotification.
            dispatchNotification(msg, notificationHandlers);
            return;
        }
        LOG.debug("Ignoring MCP message with no id and no method: {}", msg);
    }

    private static void dispatchResponse(JsonNode msg,
                                          Map<Integer, CompletableFuture<JsonNode>> pending) {
        int id = msg.get("id").asInt();
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            LOG.debug("MCP response for unknown request id {} — ignoring", id);
            return;
        }
        if (msg.has("error")) {
            future.completeExceptionally(new McpException(msg.get("error").toString()));
        } else {
            future.complete(msg.has("result") ? msg.get("result") : msg);
        }
    }

    private static void dispatchServerRequest(JsonNode msg,
                                                Map<String, ServerRequestHandler> handlers,
                                                Consumer<ObjectNode> replySender) {
        String method = msg.get("method").asText();
        JsonNode id = msg.get("id");
        ServerRequestHandler handler = handlers.get(method);
        ObjectNode reply = JsonUtils.getMapper().createObjectNode();
        reply.put("jsonrpc", "2.0");
        reply.set("id", id);

        if (handler == null) {
            LOG.debug("No handler registered for inbound MCP request {} — replying MethodNotFound", method);
            ObjectNode err = reply.putObject("error");
            err.put("code", -32601);
            err.put("message", "Method not found");
        } else {
            try {
                JsonNode result = handler.handle(msg.get("params"));
                if (result == null) reply.putNull("result");
                else reply.set("result", result);
            } catch (Exception e) {
                LOG.warn("Handler for inbound MCP request {} threw: {}", method, e.getMessage());
                ObjectNode err = reply.putObject("error");
                err.put("code", -32603);
                err.put("message", "Internal error: " + e.getMessage());
            }
        }
        try {
            replySender.accept(reply);
        } catch (Exception e) {
            LOG.warn("Failed to send reply for inbound MCP request {}: {}", method, e.getMessage());
        }
    }

    private static void dispatchNotification(JsonNode msg,
                                              Map<String, NotificationHandler> handlers) {
        String method = msg.get("method").asText();
        NotificationHandler handler = handlers.get(method);
        if (handler == null) {
            LOG.debug("No handler registered for notification {} — ignoring (spec)", method);
            return;
        }
        try {
            handler.handle(msg.get("params"));
        } catch (Exception e) {
            LOG.warn("Notification handler for {} threw: {}", method, e.getMessage());
        }
    }
}
