package com.claudecode.mcp;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

/**
 * Locks in {@link McpMessageDispatcher}'s three-way classification
 * (response / server request / notification) plus the JSON-RPC error
 * paths (unknown method → -32601, handler throw → -32603).
 */
class McpMessageDispatcherTest {

    private static ObjectNode obj() { return JsonUtils.getMapper().createObjectNode(); }

    // ── response path ────────────────────────────────────────────────────────

    @Test
    void response_completesPendingFuture_withResult() throws Exception {
        var pending = new ConcurrentHashMap<Integer, CompletableFuture<JsonNode>>();
        var f = new CompletableFuture<JsonNode>();
        pending.put(7, f);

        ObjectNode msg = obj();
        msg.put("id", 7);
        msg.set("result", obj().put("ok", true));

        McpMessageDispatcher.dispatch(msg, pending, Map.of(), Map.of(), _ -> {});

        assertTrue(f.isDone());
        assertTrue(f.get().get("ok").asBoolean());
        assertFalse(pending.containsKey(7), "future must be evicted from pending after response");
    }

    @Test
    void response_withError_completesFutureExceptionally() {
        var pending = new ConcurrentHashMap<Integer, CompletableFuture<JsonNode>>();
        var f = new CompletableFuture<JsonNode>();
        pending.put(9, f);

        ObjectNode msg = obj();
        msg.put("id", 9);
        msg.set("error", obj().put("code", -1).put("message", "boom"));

        McpMessageDispatcher.dispatch(msg, pending, Map.of(), Map.of(), _ -> {});

        assertTrue(f.isCompletedExceptionally());
        assertThrows(Exception.class, f::get);
    }

    @Test
    void response_forUnknownId_isSilentlyIgnored() {
        // Log line only; no state change, no exception.
        McpMessageDispatcher.dispatch(
            obj().put("id", 999).set("result", obj()),
            new ConcurrentHashMap<>(), Map.of(), Map.of(), _ -> {});
    }

    // ── server request path ──────────────────────────────────────────────────

    @Test
    void serverRequest_invokesHandler_andReplyIsSentWithMatchingId() {
        AtomicReference<ObjectNode> capturedReply = new AtomicReference<>();
        Map<String, ServerRequestHandler> handlers = Map.of(
            "roots/list", _ -> {
                ObjectNode r = obj();
                r.putArray("roots").add(obj().put("uri", "file:///cwd"));
                return r;
            });

        ObjectNode req = obj();
        req.put("id", 42);
        req.put("method", "roots/list");

        McpMessageDispatcher.dispatch(req, new ConcurrentHashMap<>(), handlers, Map.of(),
            capturedReply::set);

        ObjectNode reply = capturedReply.get();
        assertNotNull(reply, "handler match must trigger a reply");
        assertEquals(42, reply.get("id").asInt(), "reply id must echo request id");
        assertEquals("2.0", reply.get("jsonrpc").asText());
        assertEquals("file:///cwd",
            reply.get("result").get("roots").get(0).get("uri").asText());
        assertFalse(reply.has("error"));
    }

    @Test
    void serverRequest_unknownMethod_repliesMethodNotFound() {
        AtomicReference<ObjectNode> capturedReply = new AtomicReference<>();

        ObjectNode req = obj();
        req.put("id", 1);
        req.put("method", "unknown/method");

        McpMessageDispatcher.dispatch(req, new ConcurrentHashMap<>(), Map.of(), Map.of(),
            capturedReply::set);

        ObjectNode reply = capturedReply.get();
        assertNotNull(reply);
        assertEquals(-32601, reply.get("error").get("code").asInt(),
            "unknown method must reply with JSON-RPC MethodNotFound (-32601)");
        assertEquals("Method not found", reply.get("error").get("message").asText(),
            "MCP SDK 1.29.0 uses the stable generic MethodNotFound message");
    }

    @Test
    void serverRequest_handlerThrows_repliesInternalError() {
        AtomicReference<ObjectNode> capturedReply = new AtomicReference<>();
        Map<String, ServerRequestHandler> handlers = Map.of(
            "boom", _ -> { throw new RuntimeException("kaboom"); });

        ObjectNode req = obj();
        req.put("id", 3);
        req.put("method", "boom");

        McpMessageDispatcher.dispatch(req, new ConcurrentHashMap<>(), handlers, Map.of(),
            capturedReply::set);

        ObjectNode reply = capturedReply.get();
        assertEquals(-32603, reply.get("error").get("code").asInt(),
            "handler exception must produce InternalError (-32603)");
        assertTrue(Strings.CS.contains(reply.get("error").get("message").asText(), "kaboom"));
    }

    // ── notification path ────────────────────────────────────────────────────

    @Test
    void notification_invokesHandler_noReplyEver() {
        AtomicInteger fireCount = new AtomicInteger();
        AtomicReference<ObjectNode> anyReply = new AtomicReference<>();
        Map<String, NotificationHandler> handlers = Map.of(
            "notifications/tools/list_changed", _ -> fireCount.incrementAndGet());

        ObjectNode notif = obj();
        notif.put("method", "notifications/tools/list_changed");
        notif.set("params", obj());

        McpMessageDispatcher.dispatch(notif, new ConcurrentHashMap<>(), Map.of(), handlers,
            anyReply::set);

        assertEquals(1, fireCount.get());
        assertNull(anyReply.get(), "notifications MUST NOT trigger a reply — JSON-RPC 2.0 §4.1");
    }

    @Test
    void notification_unknownMethod_isSilentlyIgnored() {
        // Per JSON-RPC spec, unknown notifications must not produce an error reply.
        AtomicReference<ObjectNode> anyReply = new AtomicReference<>();
        McpMessageDispatcher.dispatch(
            obj().put("method", "unknown/notification"),
            new ConcurrentHashMap<>(), Map.of(), Map.of(),
            anyReply::set);
        assertNull(anyReply.get());
    }

    @Test
    void notification_handlerThrows_isSwallowed() {
        Map<String, NotificationHandler> handlers = Map.of(
            "notifications/thing", _ -> { throw new RuntimeException("swallowed"); });
        // No throw expected — dispatcher must not propagate errors from notification handlers.
        McpMessageDispatcher.dispatch(
            obj().put("method", "notifications/thing"),
            new ConcurrentHashMap<>(), Map.of(), handlers, _ -> {});
    }

    // ── malformed input ──────────────────────────────────────────────────────

    @Test
    void nullOrNonObjectMessage_isSilentlyIgnored() {
        // Both are legal inputs from a wire perspective (e.g. keep-alive frames);
        // dispatcher must not throw.
        McpMessageDispatcher.dispatch(null, new ConcurrentHashMap<>(), Map.of(), Map.of(), _ -> {});
        McpMessageDispatcher.dispatch(
            JsonUtils.getMapper().createArrayNode(),
            new ConcurrentHashMap<>(), Map.of(), Map.of(), _ -> {});
    }
}
