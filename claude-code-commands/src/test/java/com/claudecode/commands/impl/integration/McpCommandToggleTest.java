package com.claudecode.commands.impl.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.runtime.mcp.McpManagementPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpCommandToggleTest {
    @Test void enablesAllDisabledServersThroughNeutralActions() {
        FakeMcp mcp = new FakeMcp(List.of(server("one", true), server("two", true)));
        assertEquals("Enabled 2 MCP server(s)",
            new McpCommand(mcp).execute(CommandContext.minimal(), "enable").output());
        assertEquals(List.of("ENABLE:one", "ENABLE:two"), mcp.actions);
    }

    @Test void preservesNotFoundAlreadyAndReconnectMessages() {
        FakeMcp mcp = new FakeMcp(List.of(server("one", false)));
        McpCommand command = new McpCommand(mcp);
        assertEquals("MCP server \"missing\" not found",
            command.execute(CommandContext.minimal(), "disable missing").output());
        assertEquals("MCP server \"one\" is already enabled",
            command.execute(CommandContext.minimal(), "enable one").output());
        assertEquals("Successfully reconnected to one",
            command.execute(CommandContext.minimal(), "reconnect one").output());
    }

    @Test void headlessListUsesPortSnapshot() {
        String output = new McpCommand(new FakeMcp(List.of(server("one", false))))
            .execute(CommandContext.minimal(), "").output();
        assertTrue(Strings.CS.contains(output, "one [CONNECTED]"));
        assertTrue(Strings.CS.contains(output, "transport: stdio"));
    }

    private static McpManagementPort.Server server(String name, boolean disabled) {
        return new McpManagementPort.Server(name, name, "project", 0,
            disabled ? McpManagementPort.Status.DISABLED : McpManagementPort.Status.CONNECTED,
            McpManagementPort.AuthStatus.NOT_APPLICABLE, "", false, true,
            "stdio", "node", List.of("server.js"), Map.of(), null, 0, ".mcp.json");
    }

    private static final class FakeMcp implements McpManagementPort {
        private final List<Server> servers;
        private final List<String> actions = new ArrayList<>();
        private FakeMcp(List<Server> servers) { this.servers = servers; }
        @Override public List<Server> servers() { return servers; }
        @Override public List<Tool> tools(String serverName) { return List.of(); }
        @Override public String execute(Action action, String serverName) {
            actions.add(action + ":" + serverName);
            return "ok";
        }
    }
}
