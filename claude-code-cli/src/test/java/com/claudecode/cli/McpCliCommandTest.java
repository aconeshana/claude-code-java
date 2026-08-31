package com.claudecode.cli;

import com.claudecode.mcp.McpServerScope;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;








class McpCliCommandTest {

    @Test
    void bareCommandWritesUsageThroughPicocliErrorPort() {
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        var command = ClaudeCodeCli.commandLine(new ClaudeCodeCli())
            .setOut(new PrintWriter(stdout, true))
            .setErr(new PrintWriter(stderr, true));

        int exitCode = command.execute("mcp");

        assertEquals(2, exitCode);
        assertTrue(Strings.CS.contains(stderr.toString(), "Usage: claude mcp"));
        assertEquals("", stdout.toString());
    }

    @Test
    void parseScope_acceptsAllValidScopes_caseInsensitive() {
        assertEquals(McpServerScope.LOCAL,   McpCliCommand.parseScope("local"));
        assertEquals(McpServerScope.LOCAL,   McpCliCommand.parseScope("LOCAL"));
        assertEquals(McpServerScope.USER,    McpCliCommand.parseScope("user"));
        assertEquals(McpServerScope.PROJECT, McpCliCommand.parseScope("PrOjEcT"));
        // Default when null — CLI passes the field's default ("local") explicitly,
        // but the helper still needs to survive a null gracefully.
        assertEquals(McpServerScope.LOCAL,   McpCliCommand.parseScope(null));
    }

    @Test
    void parseScope_rejectsUnknownScope() {
        // ENTERPRISE isn't user-writable via the CLI; reject at parse time.
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseScope("enterprise"));
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseScope("global"));
    }

    @Test
    void normaliseTransport_acceptsKnownValues_defaultsToStdio() {
        assertEquals("stdio", McpCliCommand.normaliseTransport(null));
        assertEquals("stdio", McpCliCommand.normaliseTransport("   "));
        assertEquals("stdio", McpCliCommand.normaliseTransport("STDIO"));
        assertEquals("sse",   McpCliCommand.normaliseTransport("SSE"));
        assertEquals("http",  McpCliCommand.normaliseTransport("Http"));
    }

    @Test
    void normaliseTransport_rejectsUnknownTransport() {
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.normaliseTransport("websocket"));
    }

    @Test
    void parseHeader_splitsOnFirstColon_trimsWhitespace() {

        Map.Entry<String, String> e = McpCliCommand.parseHeader("Authorization: Bearer abc123");
        assertEquals("Authorization", e.getKey());
        assertEquals("Bearer abc123", e.getValue());
    }

    @Test
    void parseHeader_preservesValueColons() {
        // Any colon after the first belongs to the value (e.g. Bearer tokens
        // sometimes contain colons; URLs in custom headers certainly do).
        Map.Entry<String, String> e = McpCliCommand.parseHeader(
            "X-Trace: id:req:abc:xyz");
        assertEquals("X-Trace", e.getKey());
        assertEquals("id:req:abc:xyz", e.getValue());
    }

    @Test
    void parseHeader_rejectsMissingColon_orEmptyKey() {
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseHeader("no-colon-here"));
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseHeader(":value-only"));
    }

    @Test
    void parseEnv_splitsOnFirstEquals_keepsValueVerbatim() {
        Map.Entry<String, String> e = McpCliCommand.parseEnv("HOME=/Users/me");
        assertEquals("HOME", e.getKey());
        assertEquals("/Users/me", e.getValue());
    }

    @Test
    void parseEnv_preservesValueEqualsSigns() {
        // Env values sometimes contain "=" (base64 padding, query strings).
        Map.Entry<String, String> e = McpCliCommand.parseEnv("TOKEN=abc=def=");
        assertEquals("TOKEN", e.getKey());
        assertEquals("abc=def=", e.getValue());
    }

    @Test
    void parseEnv_rejectsMissingEquals_orEmptyKey() {
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseEnv("NO_EQUALS_SIGN"));
        assertThrows(IllegalArgumentException.class,
            () -> McpCliCommand.parseEnv("=value-only"));
    }
}
