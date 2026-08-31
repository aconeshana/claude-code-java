package com.claudecode.cli;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpPromptInfo;
import com.claudecode.mcp.McpPromptResult;
import com.claudecode.runtime.mcp.McpPromptPort;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Composition-root adapter for MCP prompt discovery and invocation. */
final class CliMcpPromptAdapter implements McpPromptPort {

    private final McpClientRuntime runtime;

    CliMcpPromptAdapter(McpClientRuntime runtime) {
        this.runtime = runtime;
    }

    static Definition definition(McpPromptInfo info) {
        return new Definition(info.commandName(), info.serverName(), info.promptName(),
            info.description(), info.arguments().stream()
                .map(argument -> new Argument(argument.name(), argument.required()))
                .toList());
    }

    @Override
    public List<ContentBlock> invoke(Definition definition, Map<String, String> arguments,
                                     Path toolResultsDirectory) {
        McpPromptResult result = runtime.getPrompt(
            definition.serverName(), definition.promptName(), arguments);
        return result.toContentBlocks(definition.serverName(), toolResultsDirectory);
    }
}
