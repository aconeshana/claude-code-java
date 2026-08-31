package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * In-process SDK MCP tool callback.
{@code tool} handler.</li></ul>
 */
@FunctionalInterface
public interface SdkMcpToolHandler {
    SdkMcpToolResult call(JsonNode arguments, SdkMcpToolContext context) throws Exception;
}
