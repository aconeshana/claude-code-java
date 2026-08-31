package com.claudecode.commands.prompt;

import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.commands.impl.terminal.StatuslineCommand;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.mcp.McpPromptPort;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class PromptInvocationContractTest {

    @Test
    void statuslineCarriesBuiltinProgressAndCommandScopedPermissions() {
        CommandResult result = new StatuslineCommand().execute(CommandContext.minimal(), "");

        PromptInvocation invocation = result.promptInvocation();
        assertNotNull(invocation);
        assertEquals("setting up statusLine", invocation.progressMessage());
        assertEquals(List.of("Agent", "ConfigureStatusLine"), invocation.allowedTools());
        assertEquals("builtin", invocation.source());
        assertEquals("statusline", invocation.userFacingName());
        TextBlock prompt = assertInstanceOf(TextBlock.class, invocation.content().getFirst());
        assertTrue(Strings.CS.contains(prompt.text(), "ConfigureStatusLine"), prompt.text());
        assertTrue(Strings.CS.contains(prompt.text(), "[Console]::In.ReadToEnd()"), prompt.text());
        assertTrue(Strings.CS.contains(prompt.text(), "context_window.total_input_tokens"),
            prompt.text());
        assertTrue(Strings.CS.contains(prompt.text(), "PowerShell script body"), prompt.text());
        assertTrue(Strings.CS.contains(prompt.text(), "Windows PowerShell 5.1"), prompt.text());
        assertTrue(Strings.CS.contains(prompt.text(), "ASCII display text"), prompt.text());
        assertFalse(Strings.CS.contains(prompt.text(), "Read("), prompt.text());
        assertFalse(Strings.CS.contains(prompt.text(), "Edit("), prompt.text());
        assertFalse(Strings.CS.contains(prompt.text(), "Re-read"), prompt.text());
    }

    @Test
    void mcpPromptPreservesImageContentAndMcpMetadata() {
        McpPromptPort.Definition info = new McpPromptPort.Definition(
            "mcp__vision__inspect", "vision", "inspect", "Inspect image", List.of());
        ObjectNode image = JsonUtils.getMapper().createObjectNode();
        image.put("type", "image");
        image.put("data", "aGVsbG8=");
        image.put("mimeType", "image/png");
        McpPromptPort prompts = (_, _, _) -> List.of(new ImageBlock(image));

        CommandResult result = new McpPromptCommand(info, prompts)
            .execute(CommandContext.minimal(), "");

        PromptInvocation invocation = result.promptInvocation();
        assertNotNull(invocation);
        assertTrue(result.shouldQuery());
        assertEquals("running", invocation.progressMessage());
        assertEquals("mcp", invocation.source());
        assertEquals("vision:inspect (MCP)", invocation.userFacingName());
        assertTrue(invocation.isMcp());
        assertInstanceOf(ImageBlock.class, invocation.content().getFirst());
    }
}
