package com.claudecode.tools.mcp;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class McpAuthToolDescriptionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mcpAuthTool_descriptionPorted() {
        String d = new McpAuthTool().description();
        assertTrue(Strings.CS.contains(d, "OAuth flow"), d);
        assertTrue(Strings.CS.contains(d, "authorization URL"));
        assertTrue(Strings.CS.contains(d, "requires authentication"));
    }

    @Test
    void unboundFixtureReturnsStructuredErrorInsteadOfFalseSuccess() throws Exception {
        McpAuthTool tool = new McpAuthTool();
        String raw = tool.call(MAPPER.createObjectNode(),
            ToolExecutionContext.of(new AbortController(), "auth-test"));
        JsonNode data = MAPPER.readTree(raw);
        assertEquals("error", data.path("status").asText());
        ToolResult result = tool.mapResult(raw, null,
            ToolExecutionContext.of(new AbortController(), "auth-test"));
        assertTrue(result.isError());
        assertEquals(data, result.toolUseResult());
    }

    @Test
    void schemaAndMetadataMatchCurrentTsAuthTool() {
        McpAuthTool tool = new McpAuthTool();
        assertFalse(tool.inputSchema().has("additionalProperties"));
        assertEquals(10_000, tool.maxResultSizeChars());
        assertEquals("", tool.toAutoClassifierInput(null));
    }
}
