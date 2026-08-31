package com.claudecode.mcp;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.git.GitUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads and merges MCP server configuration from multiple levels.
 */
public class McpConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(McpConfigLoader.class);
    private static final Set<McpServerScope> ALL_FILE_SCOPES = Set.of(
        McpServerScope.USER, McpServerScope.PROJECT, McpServerScope.LOCAL);

    /**
     * The active {@code --setting-sources} gate for file-backed MCP scopes.
     * Defaults to all scopes so the low-level loader remains useful outside the
     * CLI composition root (for example in standalone UI/tests).
     */
    private static volatile Set<McpServerScope> enabledFileScopes = ALL_FILE_SCOPES;

    /**
     * Applies the process-wide setting-source selection to subsequent normal
     * MCP loads. Dynamic {@code --mcp-config} sources are intentionally not
     * affected; only user/project/local file scopes are gated.
     */
    public static void configureEnabledFileScopes(Set<McpServerScope> scopes) {
        if (scopes == null) throw new IllegalArgumentException("MCP file scopes must not be null");
        Set<McpServerScope> normalized = new HashSet<>();
        for (McpServerScope scope : scopes) {
            if (scope == McpServerScope.USER || scope == McpServerScope.PROJECT
                    || scope == McpServerScope.LOCAL) {
                normalized.add(scope);
            }
        }
        enabledFileScopes = Set.copyOf(normalized);
    }

    /** Returns whether a file-backed MCP scope is enabled by the active setting-source gate. */
    public static boolean isFileScopeEnabled(McpServerScope scope) {
        return scope != null && enabledFileScopes.contains(scope);
    }

    /**
     * Loads and merges MCP configuration for the given project directory.
     */
    public static McpConfig loadConfig(Path projectDir) {
        return loadConfig(projectDir, ClaudePaths.GLOBAL_JSON, enabledFileScopes);
    }

    /**
     * Loads only the servers defined in a single scope, without merging the other scopes on top.
     */
    public static Map<String, McpServerConfig> loadScope(Path projectDir, McpServerScope scope) {
        return loadScope(projectDir, ClaudePaths.GLOBAL_JSON, scope);
    }

    /** Testable variant of {@link #loadScope(Path, McpServerScope)}. */
    static Map<String, McpServerConfig> loadScope(Path projectDir, Path globalConfigPath,
                                                  McpServerScope scope) {
        if (scope != McpServerScope.USER && scope != McpServerScope.PROJECT
                && scope != McpServerScope.LOCAL) {
            return Map.of();
        }
        return loadConfig(projectDir, globalConfigPath, Set.of(scope)).servers();
    }

    /**
     * Loads the normal MCP configuration and applies the repeatable {@code --mcp-config} CLI sources on
     * top.
     */
    public static McpConfig loadConfig(Path projectDir, List<String> explicitSources,
                                       boolean strict) {
        return loadConfig(projectDir, ClaudePaths.GLOBAL_JSON, explicitSources, strict,
            enabledFileScopes);
    }

    /** Testable variant with an injected global config path. */
    static McpConfig loadConfig(Path projectDir, Path globalConfigPath,
                                List<String> explicitSources, boolean strict) {
        return loadConfig(projectDir, globalConfigPath, explicitSources, strict, ALL_FILE_SCOPES);
    }

    /** Testable variant with an injected global path and setting-source gate. */
    static McpConfig loadConfig(Path projectDir, Path globalConfigPath,
                                List<String> explicitSources, boolean strict,
                                Set<McpServerScope> enabledScopes) {
        boolean hasExplicit = explicitSources != null && !explicitSources.isEmpty();
        if (!hasExplicit && !strict) {
            return loadConfig(projectDir, globalConfigPath, enabledScopes);
        }

        McpConfig base = strict
            ? new McpConfig(Map.of(), Map.of(), List.of(), List.of())
            : loadConfig(projectDir, globalConfigPath, enabledScopes);
        Map<String, McpServerConfig> merged = new LinkedHashMap<>(base.servers());
        Map<String, McpServerScope> scopes = new LinkedHashMap<>(base.scopes());
        List<String> warnings = new ArrayList<>(base.warnings());
        List<McpConfigWarning> diagnostics = new ArrayList<>(base.diagnostics());
        for (String source : explicitSources == null ? List.<String>of() : explicitSources) {
            if (StringUtils.isBlank(source)) continue;
            try {
                Path path = Path.of(source);
                if (Files.isRegularFile(path)) {
                    mergeFrom(path, merged, scopes, McpServerScope.DYNAMIC, warnings, diagnostics);
                    continue;
                }
                JsonNode root = JsonUtils.getMapper().readTree(source);
                mergeInline(root, source, merged, scopes, warnings, diagnostics);
            } catch (Exception e) {
                emit(warnings, diagnostics, McpServerScope.DYNAMIC,
                    McpConfigWarning.Severity.FATAL, null,
                    "Failed to parse --mcp-config: " + e.getMessage(),
                    "Failed to parse --mcp-config: " + e.getMessage());
            }
        }
        return new McpConfig(Collections.unmodifiableMap(merged),
            Collections.unmodifiableMap(scopes), List.copyOf(warnings), List.copyOf(diagnostics));
    }

/** Testable overload with an injected  path. */
    static McpConfig loadConfig(Path projectDir, Path globalConfigPath) {
        return loadConfig(projectDir, globalConfigPath, ALL_FILE_SCOPES);
    }

    /** Testable overload with an injected global path and source selection. */
    static McpConfig loadConfig(Path projectDir, Path globalConfigPath,
                                Set<McpServerScope> enabledScopes) {
        Map<String, McpServerConfig>   merged = new LinkedHashMap<>();
        Map<String, McpServerScope>    scopes = new LinkedHashMap<>();
        List<String>                   warnings = new ArrayList<>();
        List<McpConfigWarning>         diagnostics = new ArrayList<>();
        Set<McpServerScope> selected = normalizeFileScopes(enabledScopes);

        // 1. User-level config
        if (selected.contains(McpServerScope.USER)) {
            mergeFrom(globalConfigPath, merged, scopes, McpServerScope.USER, warnings, diagnostics);
        }


        if (projectDir != null && selected.contains(McpServerScope.PROJECT)) {
            List<Path> ancestors = new ArrayList<>();
            Path current = projectDir.toAbsolutePath().normalize();
            while (current.getParent() != null) {
                ancestors.add(current);
                current = current.getParent();
            }
            Collections.reverse(ancestors);
            for (Path dir : ancestors) {
                mergeFrom(dir.resolve(".mcp.json"), merged, scopes,
                    McpServerScope.PROJECT, warnings, diagnostics);
            }

        }


        if (projectDir != null && selected.contains(McpServerScope.LOCAL)) {
            mergeLocalProjectConfig(globalConfigPath, projectDir, merged, scopes,
                warnings, diagnostics);
        }

        if (projectDir != null) {
            applyProjectEnablement(globalConfigPath, projectDir, merged);
        }

        return new McpConfig(
            Collections.unmodifiableMap(merged),
            Collections.unmodifiableMap(scopes),
            List.copyOf(warnings),
            List.copyOf(diagnostics));
    }

    private static Set<McpServerScope> normalizeFileScopes(Set<McpServerScope> scopes) {
        if (scopes == null) return ALL_FILE_SCOPES;
        Set<McpServerScope> normalized = new HashSet<>();
        for (McpServerScope scope : scopes) {
            if (scope == McpServerScope.USER || scope == McpServerScope.PROJECT
                    || scope == McpServerScope.LOCAL) {
                normalized.add(scope);
            }
        }
        return Set.copyOf(normalized);
    }

/** Returns the user-level MCP config path. */
    static Path getUserConfigPath() {
        return ClaudePaths.GLOBAL_JSON;
    }


    public static String describeConfigPath(McpServerScope scope, Path projectDir) {
        return switch (scope) {
            case USER    -> getUserConfigPath().toString();
            case PROJECT -> projectDir != null
                ? projectDir.resolve(".mcp.json").toString()
                : ".mcp.json";
            case LOCAL   -> projectDir != null
                ? getUserConfigPath() + " [project: " + projectDir.toAbsolutePath().normalize() + "]"
                : getUserConfigPath() + " [project]";
            case ENTERPRISE, DYNAMIC -> scope.name().toLowerCase(Locale.ROOT);
        };
    }

    private static void mergeLocalProjectConfig(
            Path globalConfigPath, Path projectDir,
            Map<String, McpServerConfig> target,
            Map<String, McpServerScope> scopeTarget,
            List<String> warningsSink,
            List<McpConfigWarning> diagnosticsSink) {
        if (!Files.isRegularFile(globalConfigPath)) return;
        try {
            JsonNode root = JsonUtils.readJson(globalConfigPath);
            JsonNode projects = root.get("projects");
            if (projects == null || !projects.isObject()) return;
            String key = projectConfigKey(projectDir);
            JsonNode servers = projects.path(key).path("mcpServers");
            if (!servers.isObject()) return;

            Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode serverNode = entry.getValue();
                if (!serverNode.isObject()) {
                    emit(warningsSink, diagnosticsSink, McpServerScope.LOCAL,
                        McpConfigWarning.Severity.WARNING, name,
                        globalConfigPath.getFileName() + ": server \"" + name
                            + "\" is not a JSON object — skipped",
                        "server \"" + name + "\" is not a JSON object — skipped");
                    continue;
                }
                McpServerConfig config = parseServerConfig(name, serverNode);
                validateServerConfig(config, globalConfigPath, McpServerScope.LOCAL,
                    warningsSink, diagnosticsSink);
                ExpansionResult expanded = expandEnvVars(config);
                config = expanded.config();
                if (!expanded.missingVars().isEmpty()) {
                    emit(warningsSink, diagnosticsSink, McpServerScope.LOCAL,
                        McpConfigWarning.Severity.WARNING, name,
                        globalConfigPath.getFileName() + ": server \"" + name
                            + "\" has missing environment variables: "
                            + String.join(", ", expanded.missingVars()),
                        "server \"" + name + "\" has missing environment variables: "
                            + String.join(", ", expanded.missingVars()));
                }
                target.put(name, config);
                scopeTarget.put(name, McpServerScope.LOCAL);
            }
        } catch (IOException e) {
            emit(warningsSink, diagnosticsSink, McpServerScope.LOCAL,
                McpConfigWarning.Severity.FATAL, null,
                "Failed to read " + globalConfigPath.getFileName() + ": " + e.getMessage(),
                "Failed to read: " + e.getMessage());
        }
    }

/** Canonical key used by. */
    static String projectConfigKey(Path projectDir) {
        Path gitRoot = GitUtils.findCanonicalGitRoot(projectDir);
        Path keyPath = gitRoot != null ? gitRoot : projectDir.toAbsolutePath().normalize();
        return keyPath.toString().replace('\\', '/');
    }

    private static void applyProjectEnablement(Path globalConfigPath, Path projectDir,
                                               Map<String, McpServerConfig> servers) {
        if (!Files.isRegularFile(globalConfigPath) || servers.isEmpty()) return;
        try {
            JsonNode project = JsonUtils.readJson(globalConfigPath)
                .path("projects").path(projectConfigKey(projectDir));
            JsonNode disabled = project.path("disabledMcpServers");
            if (!disabled.isArray()) return;
            Set<String> disabledNames = new HashSet<>();
            for (JsonNode node : disabled) {
                if (node.isTextual()) disabledNames.add(node.asText());
            }
            servers.replaceAll((name, cfg) -> disabledNames.contains(name)
                ? new McpServerConfig(cfg.name(), cfg.command(), cfg.args(), cfg.env(), true,
                    cfg.transportType(), cfg.url(), cfg.headers())
                : cfg);
        } catch (IOException e) {
            LOG.debug("Failed to apply MCP enabled state from {}: {}", globalConfigPath, e.toString());
        }
    }











    static void mergeFrom(Path configPath,
                          Map<String, McpServerConfig> target,
                          Map<String, McpServerScope> scopeTarget,
                          McpServerScope scope,
                          List<String> warningsSink,
                          List<McpConfigWarning> diagnosticsSink) {
        if (!Files.isRegularFile(configPath)) {
            return;
        }

        String fileName = configPath.getFileName().toString();
        try {
            JsonNode root = JsonUtils.readJson(configPath);


            // has no mcpServers member, there is simply no USER-scope MCP
            // config; treating every global key as a server is both incorrect
            // and potentially secret-leaking in diagnostics.
            if (scope == McpServerScope.USER && !root.has("mcpServers")) {
                return;
            }

            mergeInline(root, configPath.toString(), target, scopeTarget,
                warningsSink, diagnosticsSink, configPath, scope);

            JsonNode loadedServers = root.has("mcpServers") ? root.get("mcpServers") : root;
            LOG.debug("Loaded MCP config from {}: {} servers", configPath,
                loadedServers != null && loadedServers.isObject() ? loadedServers.size() : 0);
        } catch (IOException e) {
            emit(warningsSink, diagnosticsSink, scope, McpConfigWarning.Severity.FATAL, null,
                "Failed to read " + fileName + ": " + e.getMessage(),
                "Failed to read: " + e.getMessage());
            LOG.warn("Failed to read MCP config from {}", configPath, e);
        }
    }

    /** Merges an inline --mcp-config JSON object using the same parser as files. */
    private static void mergeInline(JsonNode root, String sourceLabel,
                                    Map<String, McpServerConfig> target,
                                    Map<String, McpServerScope> scopeTarget,
                                    List<String> warningsSink,
                                    List<McpConfigWarning> diagnosticsSink) {
        mergeInline(root, sourceLabel, target, scopeTarget, warningsSink, diagnosticsSink,
            Path.of("<inline-mcp-config>"), McpServerScope.DYNAMIC);
    }

    private static void mergeInline(JsonNode root, String sourceLabel,
                                    Map<String, McpServerConfig> target,
                                    Map<String, McpServerScope> scopeTarget,
                                    List<String> warningsSink,
                                    List<McpConfigWarning> diagnosticsSink,
                                    Path validationPath,
                                    McpServerScope scope) {
        JsonNode mcpServers = root != null && root.has("mcpServers")
            ? root.get("mcpServers") : root;
        if (mcpServers == null || !mcpServers.isObject()) {
            String type = mcpServers == null ? "missing" : mcpServers.getNodeType().name().toLowerCase(Locale.ROOT);
            emit(warningsSink, diagnosticsSink, scope, McpConfigWarning.Severity.FATAL, null,
                "Skipped " + sourceLabel + ": top-level object is not a \"mcpServers\" map (got " + type + ")",
                "Skipped: top-level object is not a \"mcpServers\" map (got " + type + ")");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = mcpServers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey();
            JsonNode serverNode = entry.getValue();
            if (!serverNode.isObject()) {
                emit(warningsSink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, name,
                    sourceLabel + ": server \"" + name + "\" is not a JSON object — skipped",
                    "server \"" + name + "\" is not a JSON object — skipped");
                continue;
            }
            McpServerConfig config = parseServerConfig(name, serverNode);
            validateServerConfig(config, validationPath, scope, warningsSink, diagnosticsSink);
            ExpansionResult expanded = expandEnvVars(config);
            if (!expanded.missingVars().isEmpty()) {
                emit(warningsSink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, name,
                    sourceLabel + ": server \"" + name + "\" has missing environment variables: "
                        + String.join(", ", expanded.missingVars()),
                    "server \"" + name + "\" has missing environment variables: "
                        + String.join(", ", expanded.missingVars()));
            }
            config = expanded.config();
            if (target.containsKey(name)) {
                emit(warningsSink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, name,
                    sourceLabel + ": server \"" + name + "\" overrides an earlier entry from "
                        + scopeTarget.get(name) + " scope",
                    "server \"" + name + "\" overrides an earlier entry from "
                        + scopeTarget.get(name) + " scope");
            }
            target.put(name, config);
            scopeTarget.put(name, scope);
        }
    }

    /**
     * Appends a warning to both sinks at once — the flat string (with file
     * prefix, for the /mcp browser) and the structured {@link McpConfigWarning}
     * (for /doctor, which supplies its own location context via
     * {@link #describeConfigPath}).
     */
    private static void emit(List<String> warningsSink, List<McpConfigWarning> diagnosticsSink,
                             McpServerScope scope, McpConfigWarning.Severity severity,
                             String serverName, String flatMessage, String structuredMessage) {
        warningsSink.add(flatMessage);
        diagnosticsSink.add(new McpConfigWarning(scope, severity, serverName, "", structuredMessage));
    }

    /**
     * Loader-side sanity checks. These aren't hard errors (we still register
     * the server so the user can see it in {@code /mcp} and edit it) — they
     * just surface as warnings so misconfig is visible.
     */
    private static void validateServerConfig(McpServerConfig cfg, Path source, McpServerScope scope,
                                              List<String> sink, List<McpConfigWarning> diagnosticsSink) {
        String fileName = source.getFileName().toString();

        // mcp__<name>__<tool> slash-command convention and JSON round-trip.
        // We warn (not reject) for existing configs so users can still edit
        // them via /mcp; new servers are gated at write time by
        // McpConfigWriter.addServer.
        if (!McpNameNormalizer.isValidServerName(cfg.name())) {
            emit(sink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, cfg.name(),
                fileName + ": server \"" + cfg.name() + "\" has an invalid name (letters, digits, "
                    + "hyphens, underscores only) — rename it before it can be used reliably",
                "server \"" + cfg.name() + "\" has an invalid name (letters, digits, hyphens, "
                    + "underscores only) — rename it before it can be used reliably");
        }
        String transport = cfg.transportType();
        boolean known = Strings.CS.equals("stdio", transport) || Strings.CS.equals("sse", transport)
            || Strings.CS.equals("http", transport) || Strings.CS.equals("agent", transport);
        if (!known) {
            emit(sink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, cfg.name(),
                fileName + ": server \"" + cfg.name() + "\" has unknown transport type \""
                    + transport + "\" (expected: stdio | sse | http | agent)",
                "server \"" + cfg.name() + "\" has unknown transport type \"" + transport
                    + "\" (expected: stdio | sse | http | agent)");
        }
        if ((Strings.CS.equals("sse", transport) || Strings.CS.equals("http", transport))
                && (StringUtils.isBlank(cfg.url()))) {
            emit(sink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, cfg.name(),
                fileName + ": server \"" + cfg.name() + "\" is " + transport
                    + " but has no \"url\" field — will fail to connect",
                "server \"" + cfg.name() + "\" is " + transport
                    + " but has no \"url\" field — will fail to connect");
        }
        if (Strings.CS.equals("stdio", transport)
                && (StringUtils.isBlank(cfg.command()))) {
            emit(sink, diagnosticsSink, scope, McpConfigWarning.Severity.WARNING, cfg.name(),
                fileName + ": server \"" + cfg.name() + "\" is stdio "
                    + "but has no \"command\" field — will fail to launch",
                "server \"" + cfg.name() + "\" is stdio but has no \"command\" field — will fail to launch");
        }
    }

    /**
     * Parses a single server configuration node.
     */
    static McpServerConfig parseServerConfig(String name, JsonNode node) {
        String command = node.has("command") ? node.get("command").asText() : "";
        List<String> args = parseStringList(node.get("args"));
        Map<String, String> env = parseStringMap(node.get("env"));


        boolean disabled = false;
        String transportType = node.has("type") ? node.get("type").asText() : "stdio";
        String url = node.has("url") && node.get("url").isTextual()
            ? node.get("url").asText() : null;
        Map<String, String> headers = parseStringMap(node.get("headers"));

        return new McpServerConfig(
            name, command, args, env, disabled, transportType, url, headers);
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            list.add(item.asText());
        }
        return List.copyOf(list);
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        if (node == null || !node.isObject()) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            map.put(entry.getKey(), entry.getValue().asText());
        }
        return Map.copyOf(map);
    }


    record ExpansionResult(McpServerConfig config, List<String> missingVars) { }

    /**
     * Expands {@code ${VAR}} / {@code ${VAR:-default}} references in a server config's string fields
     * (command, args, env values, url, headers) against the process environment.
     */
    static ExpansionResult expandEnvVars(McpServerConfig cfg) {
        List<String> missing = new ArrayList<>();
        String command = McpUtils.expandEnvVarsInString(cfg.command(), missing);
        List<String> args = cfg.args().stream()
            .map(a -> McpUtils.expandEnvVarsInString(a, missing))
            .toList();
        Map<String, String> env = cfg.env().entrySet().stream().collect(
            LinkedHashMap::new,
            (m, e) -> m.put(e.getKey(), McpUtils.expandEnvVarsInString(e.getValue(), missing)),
            Map::putAll);
        String url = cfg.url() == null ? null : McpUtils.expandEnvVarsInString(cfg.url(), missing);
        Map<String, String> headers = cfg.headers().entrySet().stream().collect(
            LinkedHashMap::new,
            (m, e) -> m.put(e.getKey(), McpUtils.expandEnvVarsInString(e.getValue(), missing)),
            Map::putAll);
        McpServerConfig expanded = new McpServerConfig(
            cfg.name(), command, args, Map.copyOf(env), cfg.disabled(),
            cfg.transportType(), url, Map.copyOf(headers));
        return new ExpansionResult(expanded, List.copyOf(missing));
    }
}
