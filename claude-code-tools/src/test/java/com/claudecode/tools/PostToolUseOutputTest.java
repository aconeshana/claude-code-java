package com.claudecode.tools;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.PostToolUseOutputResult;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** PostToolUse replacements must pass back through the real tool mapper and storage policy. */
class PostToolUseOutputTest {

    @Test
    void replacementIsMappedBeforeStorage() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReplacementTool());

        PostToolUseOutputResult processed = registry.processPostToolUseOutput(
            "Replacement", mapper().createObjectNode(), mapper().getNodeFactory()
                .textNode("rewritten"), ToolResult.success("original"), null);

        ToolResult result = assertInstanceOf(
            PostToolUseOutputResult.Applied.class, processed).result();
        String text = assertInstanceOf(TextBlock.class, result.content().getFirst()).text();
        assertEquals("rewritten", text);
        assertEquals("rewritten",
            assertInstanceOf(JsonNode.class, result.toolUseResult()).asText());
    }

    @Test
    void invalidReplacementIsRejectedBeforeMapping() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReplacementTool());

        PostToolUseOutputResult processed = registry.processPostToolUseOutput(
            "Replacement", mapper().createObjectNode(), mapper().createObjectNode(),
            ToolResult.success("original"), ToolExecutionContext.of(
                new AbortController(), "session-hook"));

        assertInstanceOf(PostToolUseOutputResult.Rejected.class, processed);
    }

    private static ObjectMapper mapper() {
        return JsonUtils.getMapper();
    }

    private static final class ReplacementTool extends Tool<JsonNode, String> {
        private final ToolIdentity identity = new ToolIdentity("Replacement");

        @Override public ToolIdentity identity() { return identity; }
        @Override public String description() { return "fixture"; }
        @Override public JsonNode inputSchema() { return mapper().createObjectNode().put("type", "object"); }
        @Override public JsonNode outputSchema() { return mapper().createObjectNode().put("type", "string"); }
        @Override public String call(JsonNode input, ToolExecutionContext context) { return ""; }
        @Override public int maxResultSizeChars() { return 10; }
        @Override public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
            return PermissionDecision.allow();
        }
        @Override public ToolResult mapResult(Object raw, JsonNode input, ToolExecutionContext context) {
            return raw instanceof String text ? ToolResult.success(text) : null;
        }
    }
}
