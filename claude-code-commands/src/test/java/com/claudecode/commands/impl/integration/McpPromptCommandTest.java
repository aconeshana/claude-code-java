package com.claudecode.commands.impl.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.core.message.TextBlock;
import com.claudecode.runtime.mcp.McpPromptPort;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class McpPromptCommandTest {
    private static final McpPromptPort.Definition DEFINITION = new McpPromptPort.Definition(
        "mcp__server__prompt", "server", "prompt", "description",
        List.of(new McpPromptPort.Argument("first", true),
            new McpPromptPort.Argument("second", false)));

    @Test void preservesLiteralSpaceSplittingAndPromptMetadata() {
        AtomicReference<Map<String, String>> arguments = new AtomicReference<>();
        McpPromptPort port = (_, args, _) -> {
            arguments.set(args);
            return List.of(new TextBlock("result"));
        };
        var result = new McpPromptCommand(DEFINITION, port)
            .execute(CommandContext.minimal(), "one  three");
        assertEquals(Map.of("first", "one", "second", ""), arguments.get());
        assertTrue(result.shouldQuery());
        assertTrue(Strings.CS.contains(
            result.promptInvocation().precedingUserMessages().getFirst().text(),
            "mcp__server__prompt"));
    }

    @Test void failuresUseErrorChannel() {
        McpPromptPort failing = (_, _, _) -> { throw new IllegalStateException("boom"); };
        var result = new McpPromptCommand(DEFINITION, failing)
            .execute(CommandContext.minimal(), "");
        assertEquals(CommandOutputChannel.STDERR,
            result.outputChannel());
        assertEquals("Error: boom", result.output());
    }

    @Test void exposesOrderedArgumentNames() {
        assertEquals(List.of("first", "second"),
            new McpPromptCommand(DEFINITION, McpPromptPort.none()).argumentNames());
    }
}
