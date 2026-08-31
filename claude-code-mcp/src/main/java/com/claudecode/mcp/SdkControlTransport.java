package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP transport for servers hosted by an Agent SDK process and bridged over the CLI stdio control
 * protocol.
 */
public final class SdkControlTransport implements McpTransport {
    @FunctionalInterface
    public interface MessageExchange {
        CompletableFuture<JsonNode> exchange(String serverName, JsonNode message);

        /** Publishes a message whose nested control response is not observed. */
        default void send(String serverName, JsonNode message) {
            exchange(serverName, message);
        }
    }

    private final String serverName;
    private final MessageExchange exchange;
    private final AtomicInteger ids = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, ServerRequestHandler> requestHandlers = new ConcurrentHashMap<>();
    private final Map<String, NotificationHandler> notificationHandlers = new ConcurrentHashMap<>();
    private volatile boolean closed;
    /** Test hook: overrides operation timeouts when positive. */
    long requestTimeoutOverrideMs;

    public SdkControlTransport(String serverName, MessageExchange exchange) {
        this.serverName = serverName;
        this.exchange = exchange;
    }

    @Override
    public JsonNode sendRequest(String method, JsonNode params) {
        return sendRequest(method, params, null);
    }

    /** Sends an SDK MCP request tied to the current query abort signal. */
    public JsonNode sendRequest(String method, JsonNode params,
                                AbortController abortController) {
        if (closed) throw new McpException("Transport is closed");
        int id = ids.getAndIncrement();
        ObjectNode message = JsonUtils.getMapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        if (params != null) {
            JsonNode outboundParams = params;
            if (Strings.CS.equals("tools/call", method) && params.isObject()) {
                ObjectNode copy = ((ObjectNode) params).deepCopy();
                ObjectNode meta = copy.has("_meta") && copy.get("_meta").isObject()
                    ? (ObjectNode) copy.get("_meta") : copy.putObject("_meta");
                if (!meta.has("progressToken")) meta.put("progressToken", id);
                outboundParams = copy;
            }
            message.set("params", outboundParams);
        }
        if (Strings.CS.equals("tools/call", method) && abortController != null) {
            abortController.onAbort(() -> sendCancellation(id));
        }
        JsonNode response = awaitExchange(method, exchange.exchange(serverName, message));
        if (response == null) throw new McpException("SDK MCP server returned no response");
        if (response.has("error")) throw new McpException(response.path("error").toString());
        return response.has("result") ? response.get("result") : response;
    }

    private void sendCancellation(int requestId) {
        if (closed) return;
        ObjectNode message = JsonUtils.getMapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", "notifications/cancelled");
        ObjectNode params = message.putObject("params");
        params.put("requestId", requestId);
        params.put("reason", "AbortError: The operation was aborted.");

        // cancellation before acknowledging the outer interrupt/end request.
        exchange.send(serverName, message);
    }

    @Override
    public void sendNotification(String method, JsonNode params) {
        if (closed) throw new McpException("Transport is closed");
        ObjectNode message = JsonUtils.getMapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        if (params != null) message.set("params", params);
        awaitExchange(method, exchange.exchange(serverName, message));
    }

    private JsonNode awaitExchange(String method, CompletableFuture<JsonNode> future) {
        long timeoutMs = requestTimeoutOverrideMs > 0 ? requestTimeoutOverrideMs
            : McpTimeouts.operationTimeout(method).toMillis();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new McpException("SDK MCP request '" + method + "' timed out after "
                + timeoutMs + "ms", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new McpException("SDK MCP request '" + method + "' interrupted", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof McpException mcp) throw mcp;
            throw new McpException("SDK MCP request '" + method + "' failed", cause);
        }
    }

    /** Delivers SDK-server → CLI JSON-RPC notifications/requests. */
    public void receive(JsonNode message) {
        if (closed) return;
        McpMessageDispatcher.dispatch(message, pending, requestHandlers,
            notificationHandlers, reply -> exchange.exchange(serverName, reply));
    }

    @Override public boolean isConnected() { return !closed; }
    @Override public void onServerRequest(String method, ServerRequestHandler handler) {
        requestHandlers.put(method, handler);
    }
    @Override public void onNotification(String method, NotificationHandler handler) {
        notificationHandlers.put(method, handler);
    }
    @Override public void close() { closed = true; }
}
