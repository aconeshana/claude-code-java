package com.claudecode.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.Map;

/**
 * Presentation-neutral application boundary for inspecting and managing MCP servers.
 */
public interface McpManagementPort {

    enum Status { CONNECTED, DISABLED, NEEDS_AUTH, DISCONNECTED }

    enum AuthStatus { NOT_APPLICABLE, STATIC_HEADER, AUTHENTICATED, NOT_AUTHENTICATED }

    enum Action { ENABLE, DISABLE, RECONNECT, AUTHENTICATE, CLEAR_AUTHENTICATION }

    record Server(
        String name,
        String displayName,
        String scope,
        int scopeOrder,
        Status status,
        AuthStatus authStatus,
        String authDescription,
        boolean pluginChild,
        boolean manageable,
        String transport,
        String command,
        List<String> args,
        Map<String, String> environment,
        String url,
        int headerCount,
        String configLocation
    ) {
        public Server {
            args = args == null ? List.of() : List.copyOf(args);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }

        public boolean connected() { return status == Status.CONNECTED; }

        public boolean disabled() { return status == Status.DISABLED; }

        public boolean needsAuthentication() { return status == Status.NEEDS_AUTH; }

        public boolean remote() {
            return Strings.CI.equals("http", transport) || Strings.CI.equals("sse", transport);
        }

        public boolean agentProvided() { return Strings.CI.equals("agent", transport); }
    }

    record Tool(String name, String description, JsonNode inputSchema) {
        public Tool(String name, String description) {
            this(name, description, null);
        }
    }

    record Snapshot(List<Server> servers, List<String> warnings) {
        public Snapshot {
            servers = servers == null ? List.of() : List.copyOf(servers);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    List<Server> servers();

    default Snapshot snapshot() { return new Snapshot(servers(), List.of()); }

    List<Tool> tools(String serverName);

    default String execute(Action action, String serverName) {
        return switch (action) {
            case ENABLE, DISABLE -> toggle(serverName);
            case RECONNECT -> reconnect(serverName);
            case AUTHENTICATE -> authenticate(serverName);
            case CLEAR_AUTHENTICATION -> clearAuthentication(serverName);
        };
    }

    default String toggle(String serverName) {
        throw new IllegalStateException("MCP toggle is not wired");
    }

    default String reconnect(String serverName) {
        throw new IllegalStateException("MCP reconnect is not wired");
    }

    default String authenticate(String serverName) {
        throw new IllegalStateException("MCP OAuth is not wired");
    }

    default String clearAuthentication(String serverName) {
        throw new IllegalStateException("MCP OAuth is not wired");
    }

    default boolean manageable(String serverName) {
        return servers().stream().filter(server -> server.name().equals(serverName))
            .findFirst().map(Server::manageable).orElse(false);
    }

    default AuthStatus authStatus(String serverName) {
        return servers().stream().filter(server -> server.name().equals(serverName))
            .findFirst().map(Server::authStatus).orElse(AuthStatus.NOT_APPLICABLE);
    }

    static McpManagementPort none() {
        return new McpManagementPort() {
            @Override public List<Server> servers() { return List.of(); }
            @Override public List<Tool> tools(String serverName) { return List.of(); }
            @Override public String execute(Action action, String serverName) {
                throw new IllegalStateException("MCP management is not wired");
            }
        };
    }
}
