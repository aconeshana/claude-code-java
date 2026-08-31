package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigWriter;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpServerHealth;
import com.claudecode.mcp.McpServerScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Picocli subcommand tree that backs {@code claude mcp <add|remove|list>}.
 */
@Command(
    name = "mcp",
    description = "Manage Model Context Protocol (MCP) server registrations",
    mixinStandardHelpOptions = true,
    subcommands = {
        McpCliCommand.Add.class,
        McpCliCommand.Remove.class,
        McpCliCommand.List.class,
        McpCliCommand.Get.class
    }
)
public class McpCliCommand extends McpOutputCommand implements Callable<Integer> {

    /**
     * Order in which {@code remove} enumerates scopes when {@code --scope} is omitted.
     */
    private static final McpServerScope[] SCOPE_DETECT_ORDER = {
        McpServerScope.LOCAL, McpServerScope.PROJECT, McpServerScope.USER
    };

    /** Fires when the user runs bare {@code claude mcp} — print help and exit non-zero. */
    @Override
    public Integer call() {
        stderr().println("Usage: claude mcp <add|remove|list|get> [options]");
        stderr().println("Run 'claude mcp --help' for full help.");
        return 2;
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /**
     * Parses a scope name (case-insensitive) into an {@link McpServerScope}.
     * Only session-writable scopes are accepted; ENTERPRISE/DYNAMIC would
     * bail deeper in {@link McpConfigWriter} anyway, but rejecting up front
     * gives a clearer error.
     */
    static McpServerScope parseScope(String raw) {
        String s = raw == null ? "local" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "local"   -> McpServerScope.LOCAL;
            case "user"    -> McpServerScope.USER;
            case "project" -> McpServerScope.PROJECT;
            default -> throw new IllegalArgumentException(
                "Invalid --scope '" + raw + "'. Expected: local, user, or project.");
        };
    }

    /**
     * Normalises {@code --transport} to one of {stdio, sse, http}.
     */
    static String normaliseTransport(String raw) {
        if (StringUtils.isBlank(raw)) return "stdio";
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "stdio", "sse", "http" -> t;
            default -> throw new IllegalArgumentException(
                "Invalid --transport '" + raw + "'. Expected: stdio, sse, or http.");
        };
    }


    static Map.Entry<String, String> parseHeader(String entry) {
        int colon = entry.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException(
                "Invalid --header '" + entry + "'. Expected format \"Key: Value\".");
        }
        String key   = entry.substring(0, colon).trim();
        String value = entry.substring(colon + 1).trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid --header '" + entry + "'. Header name must not be empty.");
        }
        return Map.entry(key, value);
    }

    /**
     * Splits a {@code "KEY=VALUE"} env entry. Trims whitespace on the key
     * side; leaves the value verbatim (env values may legitimately contain
     * leading/trailing whitespace).
     */
    static Map.Entry<String, String> parseEnv(String entry) {
        int eq = entry.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException(
                "Invalid --env '" + entry + "'. Expected format \"KEY=VALUE\".");
        }
        String key   = entry.substring(0, eq).trim();
        String value = entry.substring(eq + 1);
        if (key.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid --env '" + entry + "'. Variable name must not be empty.");
        }
        return Map.entry(key, value);
    }

    static Path resolveCwd() {
        return Path.of(System.getProperty("user.dir"));
    }

    // ── add ──────────────────────────────────────────────────────────────────

    @Command(name = "add", description = "Register a new MCP server in mcp.json",
        mixinStandardHelpOptions = true)
    static class Add extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Server name")
        String name;

        @Option(names = {"-s", "--scope"}, defaultValue = "local",
            description = "Scope: local (default) | user | project")
        String scope;

        @Option(names = {"-t", "--transport"}, defaultValue = "stdio",
            description = "Transport: stdio (default) | sse | http")
        String transport;

        @Option(names = {"--command"}, description = "Command to execute (stdio only)")
        String command;

        @Option(names = {"--args"}, description = "Command argument (repeat for multiple)")
        java.util.List<String> args;

        @Option(names = {"-e", "--env"}, description = "Environment variable KEY=VALUE (repeatable, stdio only)")
        java.util.List<String> env;

        @Option(names = {"--url"}, description = "Endpoint URL (sse/http only)")
        String url;

        @Option(names = {"-H", "--header"}, description = "Extra HTTP header \"Key: Value\" (repeatable, sse/http only)")
        java.util.List<String> headers;

        @Option(names = {"--disabled"}, defaultValue = "false",
            description = "Register the server in disabled state")
        boolean disabled;

        @Override
        public Integer call() {
            try {
                McpServerScope srvScope = parseScope(scope);
                String tType = normaliseTransport(transport);

                // Cheap up-front name validation so the error line matches
                // the CLI-error convention (exit 2) instead of bubbling up
                // from the writer as a generic IllegalArgumentException.
                if (StringUtils.isBlank(name)
                        || !name.matches("[a-zA-Z0-9_-]+")) {
                    stderr().println("Invalid server name '" + name
                        + "': only letters, digits, hyphens (-), and "
                        + "underscores (_) are allowed.");
                    return 2;
                }

                // Field-level validation catches the common misuse patterns
                // (URL for a stdio server, command for a remote server) with
                // a clearer error than the transport/config layer would give.
                if (Strings.CS.equals("stdio", tType)) {
                    if (url != null) {
                        stderr().println("--url is not valid for stdio transport.");
                        return 2;
                    }
                    if (StringUtils.isBlank(command)) {
                        stderr().println("--command is required for stdio transport.");
                        return 2;
                    }
                } else {
                    if (command != null || (args != null && !args.isEmpty())) {
                        stderr().println("--command / --args are not valid for "
                            + tType + " transport (use --url + --header instead).");
                        return 2;
                    }
                    if (StringUtils.isBlank(url)) {
                        stderr().println("--url is required for " + tType + " transport.");
                        return 2;
                    }
                }

                Map<String, String> envMap = new LinkedHashMap<>();
                if (env != null) {
                    for (String e : env) {
                        Map.Entry<String, String> pair = parseEnv(e);
                        envMap.put(pair.getKey(), pair.getValue());
                    }
                }
                Map<String, String> headerMap = new LinkedHashMap<>();
                if (headers != null) {
                    for (String h : headers) {
                        Map.Entry<String, String> pair = parseHeader(h);
                        headerMap.put(pair.getKey(), pair.getValue());
                    }
                }

                McpServerConfig server = new McpServerConfig(
                    name,
                    command == null ? "" : command,
                    args == null ? java.util.List.of() : java.util.List.copyOf(args),
                    envMap,
                    disabled,
                    tType,
                    url,
                    headerMap
                );

                McpConfigWriter.addServer(srvScope, resolveCwd(), server);

                //   `Added {transport} MCP server {name} to {scope} config`.
                stdout().println("Added " + tType + " MCP server " + name
                    + " to " + srvScope.name().toLowerCase(Locale.ROOT) + " config");
                return 0;
            } catch (IllegalArgumentException e) {
                stderr().println(e.getMessage());
                return 2;
            } catch (Exception e) {
                stderr().println("Failed to add MCP server: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Command(name = "remove", description = "Remove an MCP server from mcp.json",
        mixinStandardHelpOptions = true)
    static class Remove extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Server name")
        String name;

        @Option(names = {"-s", "--scope"},
            description = "Scope: local | user | project (auto-detected if omitted)")
        String scope;

        @Override
        public Integer call() {
            try {
                Path cwd = resolveCwd();

                if (StringUtils.isNotBlank(scope)) {
                    McpServerScope target = parseScope(scope);
                    return removeFrom(target, cwd, /* quoteName= */ false);
                }

                // No --scope: a name may legitimately be registered in several
                // scopes at once. The merged config records only the winning
                // scope, so deleting that one would silently leave the others
                // behind — enumerate each scope's own file instead.
                java.util.List<McpServerScope> matches = new ArrayList<>();
                for (McpServerScope candidate : SCOPE_DETECT_ORDER) {
                    if (McpConfigLoader.loadScope(cwd, candidate).containsKey(name)) {
                        matches.add(candidate);
                    }
                }

                if (matches.isEmpty()) {
                    stderr().println("No MCP server found with name: \"" + name + "\"");
                    return 1;
                }
                if (matches.size() == 1) {
                    return removeFrom(matches.getFirst(), cwd, /* quoteName= */ true);
                }

                stderr().println("MCP server \"" + name + "\" exists in multiple scopes:");
                for (McpServerScope match : matches) {
                    stderr().println("  - " + match.label()
                        + " (" + McpConfigLoader.describeConfigPath(match, cwd) + ")");
                }
                stderr().println();
                stderr().println("To remove from a specific scope, use:");
                for (McpServerScope match : matches) {
                    stderr().println("  claude mcp remove \"" + name + "\" -s "
                        + match.name().toLowerCase(Locale.ROOT));
                }
                return 1;
            } catch (IllegalArgumentException e) {
                stderr().println(e.getMessage());
                return 2;
            } catch (Exception e) {
                stderr().println("Failed to remove MCP server: " + e.getMessage());
                return 1;
            }
        }


        private int removeFrom(McpServerScope target, Path cwd, boolean quoteName)
            throws IOException {
            boolean removed = McpConfigWriter.removeServer(target, cwd, name);
            String scopeName = target.name().toLowerCase(Locale.ROOT);
            if (!removed) {
                stderr().println("MCP server " + name + " not present in "
                    + scopeName + " config — nothing to do.");
                return 1;
            }
            String shown = quoteName ? "\"" + name + "\"" : name;
            stdout().println("Removed MCP server " + shown + " from " + scopeName + " config");
            stdout().println("File modified: "
                + McpConfigLoader.describeConfigPath(target, cwd));
            return 0;
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Command(name = "list", description = "List all configured MCP servers grouped by scope",
        mixinStandardHelpOptions = true)
    static class List extends McpOutputCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            McpConfig cfg = McpConfigLoader.loadConfig(resolveCwd());
            if (cfg.servers().isEmpty()) {
                stdout().println("No MCP servers configured. Use `claude mcp add` to add a server.");
                return 0;
            }

            stdout().println("Checking MCP server health...");
            stdout().println();

            Map<String, String> statuses = McpServerHealth.checkAll(cfg.servers());
            for (Map.Entry<String, McpServerConfig> entry : cfg.servers().entrySet()) {
                String n = entry.getKey();
                McpServerConfig s = entry.getValue();
                String row = describe(n, s);

                // server whose type matches none of the printable transports
                // (notably "sdk", which is process-internal) produces no line.
                if (row == null) continue;
                String status = statuses.getOrDefault(n, McpServerHealth.ERROR);
                stdout().println(row + " - " + status);
            }
            return 0;
        }


        private static String describe(String name, McpServerConfig s) {
            String type = s.transportType();
            if (Strings.CS.equals("sse", type)) {
                return name + ": " + s.url() + " (SSE)";
            }
            if (Strings.CS.equals("http", type)) {
                return name + ": " + s.url() + " (HTTP)";
            }
            if (StringUtils.isBlank(type) || Strings.CS.equals("stdio", type)) {
                String args = s.args().isEmpty() ? "" : " " + String.join(" ", s.args());
                return name + ": " + (s.command() == null ? "" : s.command()) + args;
            }
            return null;
        }
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Command(name = "get", description = "Show details for a single MCP server",
        mixinStandardHelpOptions = true)
    static class Get extends McpOutputCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Server name")
        String name;

        @Override
        public Integer call() {
            Path cwd = resolveCwd();
            McpConfig cfg = McpConfigLoader.loadConfig(cwd);
            McpServerConfig server = cfg.servers().get(name);
            if (server == null) {
                stderr().println("No MCP server found with name: " + name);
                return 1;
            }
            McpServerScope serverScope =
                cfg.scopes().getOrDefault(name, McpServerScope.DYNAMIC);

            stdout().println(name + ":");
            stdout().println("  Scope: " + serverScope.label());
            McpServerHealth.HealthResult health = McpServerHealth.checkOneDetailed(server);
            stdout().println("  Status: " + health.status());
            if (health.issue() != null) {
                stdout().println("  Issue: " + health.issue());
            }

            String type = server.transportType();
            if (Strings.CS.equals("sse", type) || Strings.CS.equals("http", type)) {
                stdout().println("  Type: " + type);
                stdout().println("  URL: " + server.url());
                if (server.headers() != null && !server.headers().isEmpty()) {
                    stdout().println("  Headers:");
                    for (Map.Entry<String, String> h : server.headers().entrySet()) {
                        stdout().println("    " + h.getKey() + ": " + h.getValue());
                    }
                }
            } else if (Strings.CS.equals("stdio", type)) {
                stdout().println("  Type: stdio");
                stdout().println("  Command: " + server.command());
                stdout().println("  Args: " + String.join(" ", server.args()));
                if (server.env() != null && !server.env().isEmpty()) {
                    stdout().println("  Environment:");
                    for (Map.Entry<String, String> e : server.env().entrySet()) {
                        stdout().println("    " + e.getKey() + "=" + e.getValue());
                    }
                }
            }

            stdout().println();
            stdout().println("To remove this server, run: claude mcp remove \"" + name
                + "\" -s " + serverScope.name().toLowerCase(Locale.ROOT));
            return 0;
        }
    }
}
