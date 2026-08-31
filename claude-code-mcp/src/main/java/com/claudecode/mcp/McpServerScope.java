package com.claudecode.mcp;

/**
 * Which configuration file an MCP server was loaded from.
 */
public enum McpServerScope {
    PROJECT,
    LOCAL,
    USER,
    ENTERPRISE,
    DYNAMIC;


    public String label() {
        return switch (this) {
            case PROJECT    -> "Project config (shared via .mcp.json)";
            case LOCAL      -> "Local config (private to you in this project)";
            case USER       -> "User config (available in all your projects)";
            case ENTERPRISE -> "Enterprise config (managed by your organization)";
            case DYNAMIC    -> "Dynamic config (from command line)";
        };
    }
}
