package com.claudecode.mcp;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpUtilsTest {

    // ── expandEnvVarsInString ──────────────────────────────────────────────

    @Test
    void expandsSimpleVar() {
        // HOME (and USER) exist in the test process environment on macOS/Linux CI.
        String out = McpUtils.expandEnvVarsInString("${HOME}/bin", List.of());
        assertEquals(System.getenv("HOME") + "/bin", out);
    }

    @Test
    void expandsVarWithDefault() {
        String out = McpUtils.expandEnvVarsInString("${NO_SUCH_VAR_XYZ:-fallback}", List.of());
        assertEquals("fallback", out);
    }

    @Test
    void expandsPresentVarIgnoresDefault() {
        String out = McpUtils.expandEnvVarsInString("${HOME:-fallback}", List.of());
        assertEquals(System.getenv("HOME"), out);
    }

    @Test
    void recordsMissingVarAndLeavesToken() {
        List<String> missing = new ArrayList<>();
        String out = McpUtils.expandEnvVarsInString("x${NO_SUCH_VAR_XYZ}y", missing);
        assertEquals("x${NO_SUCH_VAR_XYZ}y", out);
        assertEquals(List.of("NO_SUCH_VAR_XYZ"), missing);
    }

    @Test
    void leavesPlainStringUntouched() {
        List<String> missing = new ArrayList<>();
        assertEquals("plain", McpUtils.expandEnvVarsInString("plain", missing));
        assertTrue(missing.isEmpty());
    }

    @Test
    void nullInputReturnsNull() {
        assertNull(McpUtils.expandEnvVarsInString(null, List.of()));
    }

    // ── expandEnvVars (whole config, via McpConfigLoader) ──────────────────

    @Test
    void expandsServerConfigFields() {
        McpServerConfig cfg = new McpServerConfig(
            "srv", "${HOME}/tool", List.of("${USER}", "lit"),
            Map.of("TOKEN", "${HOME}"), false, "stdio",
            null, Map.of("X", "${HOME}"));
        McpConfigLoader.ExpansionResult r = McpConfigLoader.expandEnvVars(cfg);
        assertEquals(System.getenv("HOME") + "/tool", r.config().command());
        assertEquals(List.of(System.getenv("USER"), "lit"), r.config().args());
        assertEquals(Map.of("TOKEN", System.getenv("HOME")), r.config().env());
        assertNull(r.config().url());
        assertEquals(Map.of("X", System.getenv("HOME")), r.config().headers());
        assertTrue(r.missingVars().isEmpty());
    }

    @Test
    void reportsMissingVarsFromConfig() {
        McpServerConfig cfg = new McpServerConfig(
            "srv", "${NO_SUCH_VAR_XYZ}", List.of(), Map.of(), false, "stdio", null, Map.of());
        McpConfigLoader.ExpansionResult r = McpConfigLoader.expandEnvVars(cfg);
        assertEquals(List.of("NO_SUCH_VAR_XYZ"), r.missingVars());
        assertEquals("${NO_SUCH_VAR_XYZ}", r.config().command());
    }

    // ── truncateDescription ────────────────────────────────────────────────

    @Test
    void keepsShortDescription() {
        assertEquals("hi", McpUtils.truncateDescription("hi"));
    }

    @Test
    void truncatesLongDescription() {
        String longDesc = "a".repeat(3000);
        String out = McpUtils.truncateDescription(longDesc);
        assertEquals(McpUtils.MAX_MCP_DESCRIPTION_LENGTH + "… [truncated]".length(), out.length());
        assertTrue(Strings.CS.endsWith(out, "… [truncated]"));
        assertTrue(Strings.CS.startsWith(out, "a".repeat(50)));
    }

    @Test
    void nullDescriptionStaysNull() {
        assertNull(McpUtils.truncateDescription(null));
    }

    // ── getLoggingSafeMcpBaseUrl ───────────────────────────────────────────

    @Test
    void stripsQueryAndTrailingSlash() {
        McpServerConfig cfg = new McpServerConfig(
            "s", "", List.of(), Map.of(), false, "http",
            "https://example.com/mcp/?token=secret", Map.of());
        assertEquals("https://example.com/mcp", McpUtils.getLoggingSafeMcpBaseUrl(cfg));
    }

    @Test
    void keepsUrlWithoutQuery() {
        McpServerConfig cfg = new McpServerConfig(
            "s", "", List.of(), Map.of(), false, "http",
            "https://example.com/path", Map.of());
        assertEquals("https://example.com/path", McpUtils.getLoggingSafeMcpBaseUrl(cfg));
    }

    @Test
    void noUrlReturnsNull() {
        McpServerConfig cfg = new McpServerConfig(
            "s", "", List.of(), Map.of(), false, "stdio", null, Map.of());
        assertNull(McpUtils.getLoggingSafeMcpBaseUrl(cfg));
    }

    @Test
    void nullConfigReturnsNull() {
        assertNull(McpUtils.getLoggingSafeMcpBaseUrl(null));
    }
}
