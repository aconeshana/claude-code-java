package com.claudecode.mcp;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes MCP configuration by scope. User and local state use the global
 * configuration, while project state uses {@code .mcp.json}. Enablement is
 * stored in the current project's {@code disabledMcpServers} array.
 *
 * <p>All writes use temp-file replacement and preserve unrelated fields.
 */
public final class McpConfigWriter {

    private static final Logger LOG = LoggerFactory.getLogger(McpConfigWriter.class);

    private McpConfigWriter() {}

    /** Returns the physical file which owns the requested scope. */
    public static Path pathForScope(McpServerScope scope, Path projectDir) {
        return pathForScope(scope, projectDir, ClaudePaths.GLOBAL_JSON);
    }

    static Path pathForScope(McpServerScope scope, Path projectDir, Path globalConfig) {
        return switch (scope) {
            case USER, LOCAL -> globalConfig;
            case PROJECT -> projectDir == null ? null : projectDir.resolve(".mcp.json");
            case ENTERPRISE, DYNAMIC -> null;
        };
    }

    /**
     * Persists the server's enabled state in the current project's global
     * config entry. The scope identifies the server's origin for callers but
     * does not change where enablement is stored.
     */
    public static boolean setDisabled(McpServerScope scope, Path projectDir,
                                      String serverName, boolean disabled) throws IOException {
        return setDisabled(scope, projectDir, serverName, disabled, ClaudePaths.GLOBAL_JSON);
    }

    static boolean setDisabled(McpServerScope scope, Path projectDir,
                               String serverName, boolean disabled,
                               Path globalConfig) throws IOException {
        if (scope == McpServerScope.DYNAMIC) {
            throw new IllegalArgumentException("Dynamic MCP servers have no persistent enablement state");
        }
        requireProjectDir(projectDir, "toggle MCP server state");
        ObjectNode root = readObjectOrCreate(globalConfig);
        ObjectNode project = Objects.requireNonNull(
            currentProject(root, projectDir, true), "project config container");
        Set<String> names = new LinkedHashSet<>();
        JsonNode existing = project.get("disabledMcpServers");
        if (existing != null && existing.isArray()) {
            for (JsonNode node : existing) {
                if (node.isTextual()) names.add(node.asText());
            }
        }
        boolean changed = disabled ? names.add(serverName) : names.remove(serverName);
        if (!changed) return false;

        ArrayNode array = project.putArray("disabledMcpServers");
        names.forEach(array::add);
        writeAtomically(globalConfig, root);
        LOG.info("Set MCP server '{}' disabled={} in project config {}", serverName, disabled, globalConfig);
        return true;
    }

    public static void addServer(McpServerScope scope, Path projectDir,
                                 McpServerConfig server) throws IOException {
        addServer(scope, projectDir, server, ClaudePaths.GLOBAL_JSON);
    }

    static void addServer(McpServerScope scope, Path projectDir,
                          McpServerConfig server, Path globalConfig) throws IOException {
        String nameError = McpNameNormalizer.invalidNameReason(server.name());
        if (nameError != null) throw new IllegalArgumentException(nameError);
        Path path = requireWritablePath(scope, projectDir, globalConfig, "add MCP server");
        ObjectNode root = readObjectOrCreate(path);
        ObjectNode servers;
        if (scope == McpServerScope.PROJECT && !McpConfigLoader.isFileScopeEnabled(scope)) {

            servers = root.putObject("mcpServers");
        } else {
            servers = Objects.requireNonNull(
                serverContainer(root, scope, projectDir, true), "MCP server container");
        }
        if (servers.has(server.name())) {
            throw new IllegalArgumentException("MCP server '" + server.name()
                + "' already exists in " + scope.name().toLowerCase(Locale.ROOT) + " config (" + path + ")");
        }
        writeServer(servers.putObject(server.name()), server);
        writeAtomically(path, root);
        LOG.info("Added MCP server '{}' to {} scope ({})", server.name(),
            scope.name().toLowerCase(Locale.ROOT), path);
    }

    public static boolean removeServer(McpServerScope scope, Path projectDir,
                                       String serverName) throws IOException {
        return removeServer(scope, projectDir, serverName, ClaudePaths.GLOBAL_JSON);
    }

    static boolean removeServer(McpServerScope scope, Path projectDir,
                                String serverName, Path globalConfig) throws IOException {
        if (scope == McpServerScope.PROJECT && !McpConfigLoader.isFileScopeEnabled(scope)) {

            // removal is a no-op for this boolean Java facade.
            return false;
        }
        Path path = requireWritablePath(scope, projectDir, globalConfig, "remove MCP server");
        if (!Files.isRegularFile(path)) return false;
        ObjectNode root = readObject(path);
        ObjectNode servers = serverContainer(root, scope, projectDir, false);
        if (servers == null || !servers.has(serverName)) return false;
        servers.remove(serverName);
        writeAtomically(path, root);
        LOG.info("Removed MCP server '{}' from {} scope ({})", serverName,
            scope.name().toLowerCase(Locale.ROOT), path);
        return true;
    }

    private static Path requireWritablePath(McpServerScope scope, Path projectDir,
                                            Path globalConfig, String operation) {
        Path path = pathForScope(scope, projectDir, globalConfig);
        if (path == null) {
            throw new IllegalArgumentException("Scope " + scope + " cannot " + operation);
        }
        if (scope == McpServerScope.LOCAL) requireProjectDir(projectDir, operation);
        return path;
    }

    private static void requireProjectDir(Path projectDir, String operation) {
        if (projectDir == null) {
            throw new IllegalArgumentException("Project directory is required to " + operation);
        }
    }

    private static ObjectNode serverContainer(ObjectNode root, McpServerScope scope,
                                              Path projectDir, boolean create) {
        return switch (scope) {
            case USER, PROJECT -> objectChild(root, "mcpServers", create);
            case LOCAL -> {
                ObjectNode project = currentProject(root, projectDir, create);
                yield project == null ? null : objectChild(project, "mcpServers", create);
            }
            case ENTERPRISE, DYNAMIC -> null;
        };
    }

    private static ObjectNode currentProject(ObjectNode root, Path projectDir, boolean create) {
        ObjectNode projects = objectChild(root, "projects", create);
        if (projects == null) return null;
        return objectChild(projects, McpConfigLoader.projectConfigKey(projectDir), create);
    }

    private static ObjectNode objectChild(ObjectNode parent, String name, boolean create) {
        JsonNode existing = parent.get(name);
        if (existing == null || existing.isNull()) {
            return create ? parent.putObject(name) : null;
        }
        if (!existing.isObject()) {
            throw new IllegalArgumentException("Expected '" + name + "' to be a JSON object");
        }
        return (ObjectNode) existing;
    }

    private static void writeServer(ObjectNode node, McpServerConfig server) {
        String transport = server.transportType();
        node.put("type", transport);
        if (Strings.CI.equals("stdio", transport)) {
            if (StringUtils.isNotBlank(server.command())) node.put("command", server.command());
            if (server.args() != null && !server.args().isEmpty()) {
                ArrayNode args = node.putArray("args");
                server.args().forEach(args::add);
            }
            if (server.env() != null && !server.env().isEmpty()) {
                ObjectNode env = node.putObject("env");
                for (Map.Entry<String, String> entry : server.env().entrySet()) {
                    env.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            if (StringUtils.isNotBlank(server.url())) node.put("url", server.url());
            if (server.headers() != null && !server.headers().isEmpty()) {
                ObjectNode headers = node.putObject("headers");
                for (Map.Entry<String, String> entry : server.headers().entrySet()) {
                    headers.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static ObjectNode readObjectOrCreate(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return JsonUtils.getMapper().createObjectNode();
        return readObject(path);
    }

    private static ObjectNode readObject(Path path) throws IOException {
        JsonNode root = JsonUtils.readJson(path);
        if (root == null || !root.isObject()) {
            throw new IOException("Top-level MCP config is not a JSON object at " + path);
        }
        return (ObjectNode) root;
    }

    private static void writeAtomically(Path path, JsonNode root) throws IOException {
        FileUtils.atomicReplace(path, temp -> JsonUtils.writeJson(temp, root, true));
    }
}
