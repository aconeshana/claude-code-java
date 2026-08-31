package com.claudecode.mcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpTimeoutsTest {

    @Test
    void usesTsTimeoutDefaultsForHeadersNormalRequestsAndTools() {
        assertEquals(Duration.ofSeconds(60), McpTimeouts.responseHeadersTimeout());
        assertEquals(Duration.ofSeconds(30),
            McpTimeouts.resolveOperationTimeout("tools/list", null, null));
        assertEquals(Duration.ofMillis(100_000_000),
            McpTimeouts.resolveOperationTimeout("tools/call", null, null));
    }

    @Test
    void resolvesPositiveEnvironmentOverridesAndRejectsInvalidValues() {
        assertEquals(Duration.ofSeconds(7),
            McpTimeouts.resolveOperationTimeout("tools/list", null, "7000"));
        assertEquals(Duration.ofSeconds(9),
            McpTimeouts.resolveOperationTimeout("tools/call", "9000", "7000"));
        assertEquals(Duration.ofMillis(100_000_000),
            McpTimeouts.resolveOperationTimeout("tools/call", "invalid", null));
    }

    @Test
    void resolvesReleased197ConnectionTimeoutPolicy() {
        assertEquals(30_000L, McpTimeouts.resolveConnectionTimeoutMillis(null));
        assertEquals(30_000L, McpTimeouts.resolveConnectionTimeoutMillis("0"));
        assertEquals(30_000L, McpTimeouts.resolveConnectionTimeoutMillis("-1"));
        assertEquals(30_000L, McpTimeouts.resolveConnectionTimeoutMillis("invalid"));
        assertEquals(7_000L, McpTimeouts.resolveConnectionTimeoutMillis("7000"));
    }
}
