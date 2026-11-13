package com.claudecode.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.process.ExecutableFinder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the start-failure diagnostic. Under CreateProcess a bare command name
 * resolves only .exe, so a .cmd shim on the PATH (npx on a default node
 * install) fails with error=2 even though naming it explicitly would launch.
 * The error must carry the cmd /c remedy official Claude Code documents for
 * Windows instead of a bare CreateProcess error code.
 */
class StdioTransportStartMessageTest {

    private static final IOException CAUSE = new IOException("CreateProcess error=2");

    @Test
    void cmdShimResolutionAddsTheCmdCRemedy() {
        String message = StdioTransport.startFailureMessage(CAUSE, "fetch",
            "npx", true,
            _ -> Optional.of(Path.of("C:\\Users\\me\\AppData\\Roaming\\npm\\npx.CMD")));
        assertTrue(Strings.CS.startsWith(message, "Failed to start MCP server 'fetch'"), message);
        assertTrue(Strings.CS.contains(message, "cmd /c"), message);
        assertTrue(Strings.CS.contains(message, "npx.CMD"), message);
        assertTrue(Strings.CS.contains(message, "as official Claude Code documents"), message);
    }

    @Test
    void realExecutableGetsNoHint() {
        String message = StdioTransport.startFailureMessage(CAUSE, "fetch",
            "node", true,
            _ -> Optional.of(Path.of("C:\\Program Files\\nodejs\\node.exe")));
        assertEquals("Failed to start MCP server 'fetch'", message);
    }

    @Test
    void unresolvedCommandGetsNoHint() {
        String message = StdioTransport.startFailureMessage(CAUSE, "fetch",
            "no-such-tool-xyz", true, _ -> Optional.empty());
        assertEquals("Failed to start MCP server 'fetch'", message);
    }

    @Test
    void posixGetsNoHint() {
        String message = StdioTransport.startFailureMessage(CAUSE, "fetch",
            "npx", false,
            _ -> Optional.of(Path.of("/usr/local/bin/npx")));
        assertFalse(Strings.CS.contains(message, "cmd /c"), message);
    }

    /**
     * End to end over the real launch path: bare "npx" fails even though the
     * shim sits on PATH, and the error carries the remedy. Requires node/npx.
     */
    @Test
    void realBareNpxFailureSurfacesTheRemedy() {
        var shim = ExecutableFinder.find("npx");
        assumeTrue(shim.isPresent()
                && Strings.CS.endsWith(
                    shim.get().getFileName().toString().toLowerCase(Locale.ROOT), ".cmd"),
            "node/npx not installed as a .cmd shim on this host");
        StdioTransport transport = new StdioTransport(new McpServerConfig("shimmed",
            "npx", List.of("--version"), Map.of(), false, "stdio", null, Map.of()));
        McpException error = assertThrows(McpException.class, transport::start);
        assertTrue(Strings.CS.contains(error.getMessage(), "cmd /c"), error.getMessage());
        assertTrue(Strings.CS.contains(error.getMessage(), "'shimmed'"), error.getMessage());
    }

    /**
     * The complementary contract: naming the shim explicitly (absolute .cmd
     * path) launches — CreateProcess auto-spawns cmd.exe for it — so no hint
     * is needed there and the server starts normally.
     */
    @Test
    void explicitCmdPathLaunchesWithoutHint(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("fakemcpserver.cmd"), "@echo off\r\nexit /b 1\r\n");
        StdioTransport transport = new StdioTransport(new McpServerConfig("shimmed",
            temp.resolve("fakemcpserver.cmd").toString(),
            List.of(), Map.of(), false, "stdio", null, Map.of()));
        transport.start();
        transport.close();
    }
}
