package com.claudecode.mcp;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class McpServerHealthTest {

    private static final McpServerConfig CONFIG = new McpServerConfig(
        "test", "cmd", List.of(), Map.of(), false, "stdio");

    @Test
    void figuresMatchReleased197MainAndFallbackVariants() {
        assertEquals("✔", McpServerHealth.tickFor(false, Map.of("TERM", "xterm-256color")));
        assertEquals("✘", McpServerHealth.crossFor(false, Map.of("TERM", "xterm-256color")));
        assertEquals("√", McpServerHealth.tickFor(false, Map.of("TERM", "linux")));
        assertEquals("×", McpServerHealth.crossFor(false, Map.of("TERM", "linux")));

        assertEquals("✔", McpServerHealth.tickFor(true, Map.of("WT_SESSION", "1")));
        assertEquals("✘", McpServerHealth.crossFor(true,
            Map.of("TERMINAL_EMULATOR", "JetBrains-JediTerm")));
        assertEquals("√", McpServerHealth.tickFor(true, Map.of()));
        assertEquals("×", McpServerHealth.crossFor(true, Map.of()));
    }

    @Test
    void connectedServerWithToolsIsProbedBeforeReportingHealthy() {
        FakeManager manager = new FakeManager(true, null, null);

        McpServerHealth.HealthResult result = McpServerHealth.check(manager, CONFIG);

        assertEquals(McpServerHealth.CONNECTED, result.status());
        assertNull(result.issue());
        assertTrue(manager.toolsProbed);
        assertTrue(manager.disconnected);
    }

    @Test
    void connectedServerWithoutToolsCapabilitySkipsToolProbe() {
        FakeManager manager = new FakeManager(false, null, null);

        McpServerHealth.HealthResult result = McpServerHealth.check(manager, CONFIG);

        assertEquals(McpServerHealth.CONNECTED, result.status());
        assertFalse(manager.toolsProbed);
    }

    @Test
    void toolFetchAuthenticationFailureUsesNeedsAuthStatus() {
        FakeManager manager = new FakeManager(true, null,
            new McpException("HTTP 401 unauthorized"));

        McpServerHealth.HealthResult result = McpServerHealth.check(manager, CONFIG);

        assertEquals(McpServerHealth.NEEDS_AUTH, result.status());
        assertNull(result.issue());
    }

    @Test
    void nonAuthToolFetchFailureCarriesReleasedStatusAndNormalizedIssue() {
        FakeManager manager = new FakeManager(true, null,
            new McpException("schema\n  validation failed"));

        McpServerHealth.HealthResult result = McpServerHealth.check(manager, CONFIG);

        assertEquals(McpServerHealth.TOOLS_FETCH_FAILED, result.status());
        assertEquals("schema validation failed", result.issue());
    }

    @Test
    void expectedConnectFailureAndUnexpectedErrorUseDifferentStatuses() {
        assertEquals(McpServerHealth.FAILED,
            McpServerHealth.check(new FakeManager(false,
                new McpException("process exited"), null), CONFIG).status());
        assertEquals(McpServerHealth.ERROR,
            McpServerHealth.check(new FakeManager(false,
                new IllegalStateException("bug"), null), CONFIG).status());
    }

    private static final class FakeManager extends McpClientManager {
        private final boolean toolsCapability;
        private final RuntimeException connectFailure;
        private final RuntimeException toolsFailure;
        private boolean toolsProbed;
        private boolean disconnected;

        private FakeManager(boolean toolsCapability, RuntimeException connectFailure,
                            RuntimeException toolsFailure) {
            this.toolsCapability = toolsCapability;
            this.connectFailure = connectFailure;
            this.toolsFailure = toolsFailure;
        }

        @Override public void connect(McpServerConfig config) {
            if (connectFailure != null) throw connectFailure;
        }

        @Override boolean serverSupportsTools(String serverId) {
            return toolsCapability;
        }

        @Override void verifyToolsForServer(String serverId) {
            toolsProbed = true;
            if (toolsFailure != null) throw toolsFailure;
        }

        @Override public void disconnect(String serverId) {
            disconnected = true;
        }

        @Override public void close() {
            // No owned resources in the fake.
        }
    }
}
