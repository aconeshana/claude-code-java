package com.claudecode.runtime.mcp;

import com.claudecode.core.message.ContentBlock;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Application boundary for invoking prompts exposed by MCP servers.
 *
 * <ul>
 *   <li>dynamic
 *       prompt definitions, ordered arguments and {@code prompts/get} dispatch.</li>
 * </ul>
 */
@FunctionalInterface
public interface McpPromptPort {

    record Argument(String name, boolean required) { }

    record Definition(String commandName, String serverName, String promptName,
                      String description, List<Argument> arguments) {
        public Definition {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }

    List<ContentBlock> invoke(Definition definition, Map<String, String> arguments,
                              Path toolResultsDirectory) throws Exception;

    static McpPromptPort none() {
        return (_, _, _) -> {
            throw new IllegalStateException("MCP prompt invocation is not wired");
        };
    }
}
