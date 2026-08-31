package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SdkControlTransportTest {
    @Test
    void nonToolRequestTimesOutInsteadOfWaitingForever() {
        SdkControlTransport transport = new SdkControlTransport("sdk",
            (_, _) -> new CompletableFuture<>());
        transport.requestTimeoutOverrideMs = 25;

        McpException failure = assertThrows(McpException.class,
            () -> transport.sendRequest("tools/list", JsonUtils.getMapper().createObjectNode()));

        assertTrue(Strings.CS.contains(failure.getMessage(), "timed out"));
    }

    @Test
    void toolCallAbortSendsThe197CancellationNotificationEvenAfterTheResultArrived() {
        CopyOnWriteArrayList<JsonNode> outbound = new CopyOnWriteArrayList<>();
        SdkControlTransport transport = new SdkControlTransport("sdk", (_, message) -> {
            outbound.add(message.deepCopy());
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("jsonrpc", "2.0");
            if (message.has("id")) response.set("id", message.get("id"));
            response.putObject("result");
            return CompletableFuture.completedFuture(response);
        });
        AbortController controller = new AbortController();

        transport.sendRequest("tools/call",
            JsonUtils.getMapper().createObjectNode().put("name", "sdk_echo"), controller);
        controller.abort();

        assertEquals(2, outbound.size());
        assertEquals(0, outbound.getFirst().path("id").asInt());
        JsonNode cancelled = outbound.get(1);
        assertEquals("notifications/cancelled", cancelled.path("method").asText());
        assertEquals(0, cancelled.path("params").path("requestId").asInt());
        assertEquals("AbortError: The operation was aborted.",
            cancelled.path("params").path("reason").asText());
    }

    @Test
    void inboundServerRequestPublishesItsReplyWithoutBlockingTheInboundControlAck()
            throws Exception {
        AtomicReference<JsonNode> outbound = new AtomicReference<>();
        CountDownLatch exchangeStarted = new CountDownLatch(1);
        CountDownLatch controllerResponded = new CountDownLatch(1);
        SdkControlTransport transport = new SdkControlTransport("sdk", (_, message) -> {
            outbound.set(message);
            exchangeStarted.countDown();
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try {
                    assertTrue(controllerResponded.await(2, TimeUnit.SECONDS));
                    response.complete(JsonUtils.getMapper().createObjectNode()
                        .put("jsonrpc", "2.0")
                        .put("method", "notifications/message"));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    response.completeExceptionally(error);
                }
            });
            return response;
        });

        ObjectNode inbound = JsonUtils.getMapper().createObjectNode();
        inbound.put("jsonrpc", "2.0");
        inbound.put("id", 900);
        inbound.put("method", "roots/list");
        inbound.putObject("params");

        CompletableFuture<Void> delivery = CompletableFuture.runAsync(
            () -> transport.receive(inbound));
        assertTrue(exchangeStarted.await(1, TimeUnit.SECONDS));
        try {
            delivery.get(250, TimeUnit.MILLISECONDS);
        } finally {
            controllerResponded.countDown();
        }
        assertNull(delivery.get(1, TimeUnit.SECONDS));
        assertEquals(900, outbound.get().path("id").asInt());
        assertEquals(-32601, outbound.get().path("error").path("code").asInt());
        assertEquals("Method not found", outbound.get().path("error").path("message").asText());
    }

    @Test
    void requestsAndInboundNotificationsUseTheSdkJsonRpcBridge() {
        AtomicReference<JsonNode> outbound = new AtomicReference<>();
        SdkControlTransport transport = new SdkControlTransport("sdk", (server, message) -> {
            assertEquals("sdk", server);
            outbound.set(message);
            ObjectNode response = JsonUtils.getMapper().createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", message.get("id"));
            response.putObject("result").put("ok", true);
            return CompletableFuture.completedFuture(response);
        });

        JsonNode result = transport.sendRequest("tools/list",
            JsonUtils.getMapper().createObjectNode());
        assertTrue(result.path("ok").asBoolean());
        assertEquals("tools/list", outbound.get().path("method").asText());
        assertEquals(0, outbound.get().path("id").asInt(),
            "the released SDK bridge starts JSON-RPC request ids at zero");

        ObjectNode callParams = JsonUtils.getMapper().createObjectNode();
        callParams.put("name", "sdk_echo");
        callParams.putObject("_meta").put("claudecode/toolUseId", "toolu_sdk");
        transport.sendRequest("tools/call", callParams);
        assertEquals(1, outbound.get().path("id").asInt());
        assertEquals("toolu_sdk", outbound.get().path("params").path("_meta")
            .path("claudecode/toolUseId").asText());
        assertEquals(1, outbound.get().path("params").path("_meta")
            .path("progressToken").asInt());

        AtomicReference<String> notification = new AtomicReference<>();
        transport.onNotification("notifications/tools/list_changed",
            params -> notification.set(params.path("marker").asText()));
        ObjectNode inbound = JsonUtils.getMapper().createObjectNode();
        inbound.put("jsonrpc", "2.0");
        inbound.put("method", "notifications/tools/list_changed");
        inbound.putObject("params").put("marker", "changed");
        transport.receive(inbound);
        assertEquals("changed", notification.get());
    }
}
