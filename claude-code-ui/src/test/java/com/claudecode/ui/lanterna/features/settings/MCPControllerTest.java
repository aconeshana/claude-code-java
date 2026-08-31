package com.claudecode.ui.lanterna.features.settings;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.mcp.McpManagementPort.AuthStatus;
import com.claudecode.runtime.mcp.McpManagementPort.Server;
import com.claudecode.runtime.mcp.McpManagementPort.Status;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.dialog.MCPSettingsDialog;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;

/** Tests the presentation controller's action-to-port routing without a live GUI. */
class MCPControllerTest {

    private static final class CapturingSink implements ReplTranscriptSink {
        final List<String> systems = new ArrayList<>();
        @Override public void system(String text) { systems.add(text); }
        @Override public void breadcrumb(String commandLabel) { }
        @Override public void line(String text, TextColor color) { }
    }

    private static Server server(String name) {
        return new Server(name, name, "project", 0, Status.DISCONNECTED,
            AuthStatus.NOT_APPLICABLE, "", false, true, "stdio", "echo",
            List.of(), Map.of(), null, 0, ".mcp.json");
    }

    @Test
    void clearAuthenticationRoutesThroughApplicationPort() {
        CapturingSink sink = new CapturingSink();
        AtomicReference<McpManagementPort.Action> action = new AtomicReference<>();
        MCPController controller = new MCPController(null, null, null, sink,
            fake((requested, _) -> {
                action.set(requested);
                return "✓ Cleared stored authentication for s";
            }));

        controller.handleMcpAction(MCPSettingsDialog.MenuAction.CLEAR_AUTH, server("s"));

        assertEquals(McpManagementPort.Action.CLEAR_AUTHENTICATION, action.get());
        assertEquals(List.of("✓ Cleared stored authentication for s"), sink.systems);
    }

    @Test
    void viewToolsAndBackAreDialogOnly() {
        CapturingSink sink = new CapturingSink();
        MCPController controller = new MCPController(null, null, null, sink,
            fake((_, _) -> { throw new AssertionError("must not execute"); }));

        controller.handleMcpAction(MCPSettingsDialog.MenuAction.VIEW_TOOLS, server("s"));
        controller.handleMcpAction(MCPSettingsDialog.MenuAction.BACK, server("s"));

        assertTrue(sink.systems.isEmpty());
    }

    @Test
    void nullServerIsIgnored() {
        CapturingSink sink = new CapturingSink();
        MCPController controller = new MCPController(null, null, null, sink,
            fake((_, _) -> { throw new AssertionError("must not execute"); }));

        controller.handleMcpAction(MCPSettingsDialog.MenuAction.RECONNECT, null);

        assertTrue(sink.systems.isEmpty());
    }

    @Test
    void backendFailureIsReportedToTranscript() {
        CapturingSink sink = new CapturingSink();
        MCPController controller = new MCPController(null, null, null, sink,
            fake((_, _) -> { throw new IllegalStateException("MCP client manager not wired"); }));

        controller.handleMcpAction(MCPSettingsDialog.MenuAction.RECONNECT, server("srv"));

        assertEquals(1, sink.systems.size());
        assertTrue(Strings.CS.contains(sink.systems.getFirst(), "not wired"));
    }

    private static McpManagementPort fake(Operation operation) {
        return new McpManagementPort() {
            @Override public List<Server> servers() { return List.of(); }
            @Override public List<Tool> tools(String serverName) { return List.of(); }
            @Override public String execute(Action action, String serverName) {
                return operation.execute(action, serverName);
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        String execute(McpManagementPort.Action action, String serverName);
    }
}
