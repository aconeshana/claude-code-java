package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.platform.Platform;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ValidationResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConfigureStatusLineToolTest {

    @Test
    void schemaIsNarrowAndStrict() {
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool((_, _) -> { }, Platform.DARWIN);

        assertEquals("object", tool.inputSchema().path("type").asText());
        assertTrue(tool.inputSchema().path("properties").has("command"));
        assertTrue(tool.inputSchema().path("properties").has("padding"));
        assertEquals("command", tool.inputSchema().path("required").get(0).asText());
        assertFalse(tool.inputSchema().path("additionalProperties").asBoolean(true));
    }

    @Test
    void blankOrMultilineCommandsAreRejectedBeforeWriting() {
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool((_, _) -> { }, Platform.DARWIN);

        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(input("   ", 0), context()));
        assertInstanceOf(ValidationResult.Invalid.class,
            tool.validateInput(input("echo safe\necho leaked", 0), context()));
    }

    @Test
    void registryRejectsUnexpectedFields() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ConfigureStatusLineTool((_, _) -> { }, Platform.DARWIN));
        ObjectNode input = input("echo safe", 0).put("settings", "secret");

        ToolResult result = registry.execute("ConfigureStatusLine", input, context());

        assertTrue(result.isError());
        assertTrue(text(result).contains("unexpected parameter `settings`"), text(result));
    }

    @Test
    void writesOnlyCommandAndPaddingWithoutEchoingTheCommand() {
        AtomicReference<String> command = new AtomicReference<>();
        AtomicInteger padding = new AtomicInteger(-1);
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool((value, amount) -> {
            command.set(value);
            padding.set(amount);
        }, Platform.DARWIN);

        String result = tool.call(input("powershell -NoProfile -Command safe", 3), context());

        assertEquals("powershell -NoProfile -Command safe", command.get());
        assertEquals(3, padding.get());
        assertEquals("Configured the user status line.", result);
        assertFalse(result.contains(command.get()));
        assertEquals(PermissionDecision.allow(), tool.checkPermissions(input("echo safe", 0), null));
    }

    @Test
    void windowsPersistsAnEncodedPowerShellInvocationInsteadOfNestedShellText() {
        AtomicReference<String> persisted = new AtomicReference<>();
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool(
            (command, _) -> persisted.set(command), Platform.WIN32);
        String script = "$raw=[Console]::In.ReadToEnd()\nWrite-Output $raw.Length";

        tool.call(input(script, 0), context());

        String prefix = "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand ";
        assertTrue(persisted.get().startsWith(prefix), persisted.get());
        String payload = persisted.get().substring(prefix.length());
        assertEquals(script, new String(Base64.getDecoder().decode(payload),
            StandardCharsets.UTF_16LE));
        assertFalse(persisted.get().contains(script));
    }

    @Test
    void windowsRejectsPowerShellSevenOnlyUnicodeEscapes() {
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool(
            (_, _) -> { }, Platform.WIN32);

        ValidationResult result = tool.validateInput(
            input("Write-Output \"`u{25B2}\"", 0), context());

        assertInstanceOf(ValidationResult.Invalid.class, result);
        assertTrue(((ValidationResult.Invalid) result).message().contains("PowerShell 5.1"));
    }

    @Test
    void omittedPaddingDefaultsToZero() {
        AtomicInteger padding = new AtomicInteger(-1);
        ConfigureStatusLineTool tool = new ConfigureStatusLineTool(
            (_, amount) -> padding.set(amount), Platform.DARWIN);
        ObjectNode input = input("echo safe", 0);
        input.remove("padding");

        tool.call(input, context());

        assertEquals(0, padding.get());
    }

    private static ObjectNode input(String command, int padding) {
        return com.claudecode.core.serialization.JsonUtils.getMapper().createObjectNode()
            .put("command", command)
            .put("padding", padding);
    }

    private static ToolExecutionContext context() {
        return ToolExecutionContext.of(new AbortController(), "statusline-test");
    }

    private static String text(ToolResult result) {
        return ((TextBlock) result.content().getFirst()).text();
    }
}
