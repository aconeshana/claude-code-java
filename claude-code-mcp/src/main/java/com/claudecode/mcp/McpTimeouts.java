package com.claudecode.mcp;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.process.SubprocessEnvironment;
import java.time.Duration;

/**
 * MCP timeout policy shared by stdio, Streamable HTTP, and legacy SSE.
 *
 * <ul>
 *   <li>60-second
 *       non-GET response-header timeout.</li>
 *   <li> —
 *       {@code MCP_TOOL_TIMEOUT}, default 100,000,000ms.</li>
 *   <li>{@code MCP_TIMEOUT}, default 30s
 *       for ordinary JSON-RPC operations.</li>
 * </ul>
 */
public final class McpTimeouts {

    private static final Duration RESPONSE_HEADERS_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofMillis(100_000_000);

    private McpTimeouts() {}

    static Duration responseHeadersTimeout() {
        return RESPONSE_HEADERS_TIMEOUT;
    }

    static Duration operationTimeout(String method) {
        return resolveOperationTimeout(method,
            SubprocessEnvironment.get("MCP_TOOL_TIMEOUT"),
            SubprocessEnvironment.get("MCP_TIMEOUT"));
    }


    public static long connectionTimeoutMillis() {
        return resolveConnectionTimeoutMillis(SubprocessEnvironment.get("MCP_TIMEOUT"));
    }

    static long resolveConnectionTimeoutMillis(String raw) {
        return positiveMillis(raw, DEFAULT_REQUEST_TIMEOUT).toMillis();
    }

    static Duration resolveOperationTimeout(String method, String toolRaw, String requestRaw) {
        boolean toolCall = Strings.CS.equals("tools/call", method);
        return positiveMillis(toolCall ? toolRaw : requestRaw,
            toolCall ? DEFAULT_TOOL_TIMEOUT : DEFAULT_REQUEST_TIMEOUT);
    }

    private static Duration positiveMillis(String raw, Duration fallback) {
        if (StringUtils.isBlank(raw)) return fallback;
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Duration.ofMillis(millis) : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }
}
