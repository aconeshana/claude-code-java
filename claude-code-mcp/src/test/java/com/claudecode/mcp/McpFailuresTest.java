package com.claudecode.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpFailuresTest {

    @Test
    void classifiesAuthenticationMarkersAcrossTheCauseChain() {
        assertTrue(McpFailures.isAuthenticationFailure(
            new IllegalStateException("connect failed", new RuntimeException("HTTP 401 unauthorized"))));
        assertTrue(McpFailures.isAuthenticationFailure(new RuntimeException("OAuth required")));
        assertFalse(McpFailures.isAuthenticationFailure(new RuntimeException("connection refused")));
        assertFalse(McpFailures.isAuthenticationFailure(null));
    }
}
