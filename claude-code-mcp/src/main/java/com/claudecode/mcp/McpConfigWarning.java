package com.claudecode.mcp;


public record McpConfigWarning(
    McpServerScope scope,
    Severity severity,
    String serverName,
    String path,
    String message
) {
    public enum Severity { FATAL, WARNING }
}
