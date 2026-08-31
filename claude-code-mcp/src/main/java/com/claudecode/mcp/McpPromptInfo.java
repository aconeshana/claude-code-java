package com.claudecode.mcp;



import java.util.List;

/**
 * Metadata for a single prompt exposed by an MCP server.
 */
public record McpPromptInfo(
    String serverName,
    String promptName,
    String description,
    List<PromptArgument> arguments
) {
    public McpPromptInfo {
        if (arguments == null) arguments = List.of();
    }


    public String commandName() {
        return "mcp__" + McpNameNormalizer.normalize(serverName) + "__" + promptName;
    }

    /**
     * Declaration of a single prompt argument.
     *
     * @param name        argument name — used as the key in {@code prompts/get}'s
     *                    {@code arguments} object
     * @param description user-facing hint, or {@code null}
     * @param required    whether this argument must be supplied
     */
    public record PromptArgument(String name, String description, boolean required) {}
}
