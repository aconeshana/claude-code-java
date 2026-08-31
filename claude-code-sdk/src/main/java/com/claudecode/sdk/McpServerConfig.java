package com.claudecode.sdk;

/** Official MCP server configuration union accepted by Query.setMcpServers. */
public sealed interface McpServerConfig permits McpStdioServerConfig, McpSseServerConfig,
        McpHttpServerConfig, McpSdkServerConfigWithInstance {}
