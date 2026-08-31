package com.claudecode.runtime.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpPromptPortTest {

    @Test
    void definitionPreservesDeclaredArgumentOrder() {
        McpPromptPort.Definition definition = new McpPromptPort.Definition(
            "mcp__server__prompt", "server", "prompt", "description",
            List.of(new McpPromptPort.Argument("first", true),
                new McpPromptPort.Argument("second", false)));

        assertEquals(List.of("first", "second"), definition.arguments().stream()
            .map(McpPromptPort.Argument::name).toList());
    }

    @Test
    void noneRejectsInvocationExplicitly() {
        McpPromptPort.Definition definition = new McpPromptPort.Definition(
            "mcp__server__prompt", "server", "prompt", "", List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> McpPromptPort.none().invoke(definition, Map.of(), Path.of("out")));
        assertEquals("MCP prompt invocation is not wired", error.getMessage());
    }
}
