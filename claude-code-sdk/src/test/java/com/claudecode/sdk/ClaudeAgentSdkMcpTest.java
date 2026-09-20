package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaudeAgentSdkMcpTest {
    private static final ObjectNode OBJECT_SCHEMA = JsonUtils.getMapper().createObjectNode()
        .put("type", "object");

    @Test
    void createsServerWithOfficialDefaultsAndMergedMetadata() throws Exception {
        SdkMcpToolDefinition tool = ClaudeAgentSdk.tool(
            "echo", "Echo input", OBJECT_SCHEMA,
            (arguments, _) -> SdkMcpToolResult.text(arguments.path("value").asText()),
            new SdkMcpToolExtras(null, "echo text", false));
        McpSdkServerConfigWithInstance config = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, "Local tools", List.of(tool), true));

        assertEquals("sdk", config.type());
        assertEquals("local", config.name());
        JsonNode initialized = config.instance().handle(request(1, "initialize", null)).get();
        assertEquals("1.0.0", initialized.path("result").path("serverInfo").path("version").asText());
        assertEquals("Local tools", initialized.path("result").path("instructions").asText());

        JsonNode listed = config.instance().handle(request(2, "tools/list", null)).get();
        JsonNode listedTool = listed.path("result").path("tools").get(0);
        assertEquals("echo text", listedTool.path("_meta").path("anthropic/searchHint").asText());
        assertTrue(listedTool.path("_meta").path("anthropic/alwaysLoad").asBoolean());
    }

    @Test
    void validatesToolInputAndReturnsCallToolResult() throws Exception {
        ObjectNode schema = JsonUtils.getMapper().createObjectNode().put("type", "object");
        schema.putArray("required").add("value");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("value").put("type", "string");
        SdkMcpToolDefinition tool = ClaudeAgentSdk.tool(
            "echo", "Echo", schema,
            (arguments, _) -> SdkMcpToolResult.text(arguments.path("value").asText()), null);
        SdkMcpServer server = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, null, List.of(tool), false)).instance();

        ObjectNode valid = JsonUtils.getMapper().createObjectNode();
        valid.put("name", "echo").putObject("arguments").put("value", "hello");
        JsonNode response = server.handle(request(3, "tools/call", valid)).get();
        assertEquals("hello", response.path("result").path("content").get(0).path("text").asText());
        assertFalse(response.path("result").path("isError").asBoolean());

        ObjectNode invalid = JsonUtils.getMapper().createObjectNode();
        invalid.put("name", "echo").putObject("arguments").put("value", 3);
        JsonNode invalidResponse = server.handle(request(4, "tools/call", invalid)).get();
        assertTrue(invalidResponse.path("result").path("isError").asBoolean());
    }

    @Test
    void closingServerFailsPendingToolCallAndSignalsAbort() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        AtomicReference<CompletableFuture<JsonNode>> pending = new AtomicReference<>();
        SdkMcpToolDefinition tool = ClaudeAgentSdk.tool(
            "wait", "Wait", OBJECT_SCHEMA,
            (_, context) -> {
                // Abort callbacks run on close()'s own thread, so this one can
                // release the handler and then hold close() there until the
                // handler's result has reached the future. That pins the
                // interleaving the old close() used to lose by chance: it
                // signalled the abort before failing the response, so the late
                // result won and a closed server answered successfully.
                // Failing the response first makes this unreachable rather
                // than merely unlikely.
                context.abortController().onAbort(() -> {
                    aborted.countDown();
                    try {
                        pending.get().get(2, TimeUnit.SECONDS);
                    } catch (Exception _) {
                        // How it completed is the assertions' business, not this callback's.
                    }
                });
                entered.countDown();
                aborted.await(2, TimeUnit.SECONDS);
                return SdkMcpToolResult.text("late");
            }, null);
        SdkMcpServer server = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, null, List.of(tool), false)).instance();
        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("name", "wait").putObject("arguments");
        pending.set(server.handle(request(5, "tools/call", params)));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        server.close();

        assertTrue(aborted.await(2, TimeUnit.SECONDS));
        assertTrue(pending.get().isCompletedExceptionally());
        assertThrows(ExecutionException.class, pending.get()::get);
    }

    @Test
    void aCallPublishedAsTheServerClosesIsStillTerminated() throws Exception {
        // call() publishes into the registry after handle() has already read
        // the closed flag, so a close() landing between the two swept a
        // registry that did not yet hold this call: nothing signalled its
        // abort, nothing completed its future, and its handler ran on a closed
        // server regardless. Racing two threads leaves that window to chance —
        // it is narrow enough that 200 rounds never hit it — so pin the
        // interleaving instead. call() derives the registry key by calling
        // asText() on the request id, and does it before the publish, so an id
        // node that blocks there parks the call in exactly the gap.
        CountDownLatch reachedCall = new CountDownLatch(1);
        CountDownLatch releaseCall = new CountDownLatch(1);
        JsonNode blockingId = new TextNode("7") {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override public String asText() {
                if (first.compareAndSet(true, false)) {
                    reachedCall.countDown();
                    try {
                        releaseCall.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.asText();
            }
        };
        CountDownLatch aborted = new CountDownLatch(1);
        SdkMcpToolDefinition tool = ClaudeAgentSdk.tool(
            "park", "Park", OBJECT_SCHEMA,
            (_, context) -> {
                context.abortController().onAbort(aborted::countDown);
                // Long enough that a call nobody aborts reads as a hang rather
                // than as a slow success.
                aborted.await(30, TimeUnit.SECONDS);
                return SdkMcpToolResult.text("late");
            }, null);
        SdkMcpServer server = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, null, List.of(tool), false)).instance();
        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("name", "park").putObject("arguments");
        ObjectNode message = JsonUtils.getMapper().createObjectNode();
        message.put("jsonrpc", "2.0");
        message.set("id", blockingId);
        message.put("method", "tools/call");
        message.set("params", params);

        CompletableFuture<CompletableFuture<JsonNode>> issued = new CompletableFuture<>();
        Thread.startVirtualThread(() -> issued.complete(server.handle(message)));
        // The call has cleared handle()'s closed check and is now parked short
        // of the publish.
        assertTrue(reachedCall.await(5, TimeUnit.SECONDS));

        server.close();
        releaseCall.countDown();

        // The publish lands on an already-swept registry. A timeout here is
        // the leak; assertThrows reports it as the wrong exception type rather
        // than hanging the suite.
        CompletableFuture<JsonNode> call = issued.get(5, TimeUnit.SECONDS);
        assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
    }

    private static ObjectNode request(int id, String method, JsonNode params) {
        ObjectNode request = JsonUtils.getMapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) request.set("params", params);
        return request;
    }
}
