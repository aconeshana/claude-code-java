package com.claudecode.sdk;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
        SdkMcpToolDefinition tool = ClaudeAgentSdk.tool(
            "wait", "Wait", OBJECT_SCHEMA,
            (_, context) -> {
                context.abortController().onAbort(aborted::countDown);
                entered.countDown();
                aborted.await(2, TimeUnit.SECONDS);
                return SdkMcpToolResult.text("late");
            }, null);
        SdkMcpServer server = ClaudeAgentSdk.createSdkMcpServer(
            new CreateSdkMcpServerOptions("local", null, null, List.of(tool), false)).instance();
        ObjectNode params = JsonUtils.getMapper().createObjectNode();
        params.put("name", "wait").putObject("arguments");
        var pending = server.handle(request(5, "tools/call", params));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        server.close();

        assertTrue(aborted.await(2, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, pending::get);
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
