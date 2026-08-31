package com.claudecode.tools.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.tools.ToolTexts;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaitForMcpServersToolTest {

    @Test
    void descriptionAndSchemaMatchOfficial197Wire() throws Exception {
        var tool = new WaitForMcpServersTool(new StubController());

        assertEquals("WaitForMcpServers", tool.name());
        assertEquals(ToolTexts.description("WaitForMcpServers"),
            tool.description());
        assertEquals("""
            Wait for MCP servers that are still connecting and whose tools are not
            yet in your tool list. Pass `servers` to wait for specific ones, or omit
            it to wait for all pending servers.

            If the user's request needs tools from a still-connecting server, call this
            tool to wait for it. Once it connects, its tools will be added to your tool
            list and you can use them directly. Returns ready=true when servers are
            ready, ready=false if they failed to connect, need authentication, or are
            disabled.

            You do not need to ask the user for confirmation to use this tool.""",
            tool.description());
        assertEquals(JsonUtils.getMapper().readTree("""
            {
              "$schema":"https://json-schema.org/draft/2020-12/schema",
              "type":"object",
              "properties":{
                "servers":{
                  "description":"Server names to wait for (default: all pending)",
                  "type":"array",
                  "items":{"type":"string"}
                }
              },
              "additionalProperties":false
            }
            """), tool.inputSchema());
        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertEquals(10_000, tool.maxResultSizeChars());
        assertInstanceOf(PermissionDecision.Allow.class,
            tool.checkPermissions(JsonUtils.getMapper().createObjectNode(), null));
    }

    @Test
    void successfulResultMatchesOfficialTextAndStructuredPayload() {
        StubController controller = new StubController();
        controller.result = new WaitForMcpServersTool.WaitResult(
            true, List.of("slow"), List.of(), List.of(), List.of(), List.of(), List.of());
        var tool = new WaitForMcpServersTool(controller);

        ToolResult result = tool.call(JsonUtils.getMapper().createObjectNode(), null);

        assertFalse(result.isError());
        assertTrue(result.includeIsErrorField());
        assertEquals("ready: true\nConnected (their tools are now available — call them directly): slow",
            ((TextBlock) result.content().getFirst()).text());
        assertEquals(controller.result, result.toolUseResult());
        assertEquals(List.of(), controller.requested);
    }

    @Test
    void unsuccessfulBucketsUseOfficialOrderAndWording() {
        StubController controller = new StubController();
        controller.result = new WaitForMcpServersTool.WaitResult(
            false,
            List.of("connected"),
            List.of("failed"),
            List.of("pending"),
            List.of("auth"),
            List.of("disabled"),
            List.of("unknown"));
        var tool = new WaitForMcpServersTool(controller);
        JsonNode input = JsonUtils.getMapper().createObjectNode()
            .set("servers", JsonUtils.getMapper().createArrayNode().add("pending").add("unknown"));

        ToolResult result = tool.call(input, null);

        assertTrue(result.isError());
        assertTrue(result.includeIsErrorField());
        assertEquals("""
            ready: false
            Connected (their tools are now available — call them directly): connected
            Failed to connect: failed
            Still connecting (try again or proceed without): pending
            Needs authentication (ask the user to run /mcp): auth
            Disabled (ask the user to enable via /mcp): disabled
            Unknown (no MCP server with this name is configured): unknown""",
            ((TextBlock) result.content().getFirst()).text());
        assertEquals(List.of("pending", "unknown"), controller.requested);
    }

    @Test
    void enabledStateTracksWhetherAnyServerIsPending() {
        StubController controller = new StubController();
        var tool = new WaitForMcpServersTool(controller);

        controller.pending = true;
        assertTrue(tool.isEnabled());
        controller.pending = false;
        assertFalse(tool.isEnabled());
    }

    private static final class StubController implements WaitForMcpServersTool.Controller {
        private boolean pending;
        private List<String> requested;
        private WaitForMcpServersTool.WaitResult result = new WaitForMcpServersTool.WaitResult(
            true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        @Override
        public boolean hasPendingServers() {
            return pending;
        }

        @Override
        public WaitForMcpServersTool.WaitResult waitForServers(List<String> servers) {
            requested = servers;
            return result;
        }
    }
}
