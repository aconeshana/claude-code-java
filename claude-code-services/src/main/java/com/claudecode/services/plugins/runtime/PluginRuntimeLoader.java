package com.claudecode.services.plugins.runtime;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.oauth.SecureStorage;
import com.claudecode.mcp.oauth.SecureStorageData;
import com.claudecode.mcp.oauth.SecureStorageFactory;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.plugins.marketplace.InstalledPlugins;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.services.plugins.marketplace.PluginChannel;
import com.claudecode.services.plugins.marketplace.PluginManifest;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.marketplace.UserConfigOption;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot.PluginSkillDir;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.core.util.FrontmatterParser;
import com.claudecode.core.prompt.ArgumentSubstitutor;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.tools.workflows.ParsedWorkflowScript;
import com.claudecode.tools.workflows.WorkflowDefinition;
import com.claudecode.tools.workflows.WorkflowMetadata;
import com.claudecode.tools.workflows.WorkflowScriptParser;
import com.claudecode.tools.workflows.WorkflowSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Enumerates the enabled plugins ({@code enabledPlugins} settings ∩ and loads every runtime
 * component into a {@link PluginRuntimeSnapshot}.
 */
public final class PluginRuntimeLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PluginRuntimeLoader.class);

    private final PluginDirectories directories;
    private final PluginSettingsStore settings;
    private final InstalledPluginsStore installedStore;
    private final SecureStorage secureStorage;
    private final List<Path> inlinePluginDirs;
    private final Set<Path> inlinePluginDirsWithoutMcp;
    private final McpbBundleLoader mcpbLoader = new McpbBundleLoader();
    private final FrontmatterParser frontmatter = FrontmatterParser.shared();

    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore) {
        this(directories, settings, installedStore, SecureStorageFactory.getInstance());
    }

    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               SecureStorage secureStorage) {
        this(directories, settings, installedStore, secureStorage, List.of(), List.of());
    }

    private PluginRuntimeLoader(PluginDirectories directories,
                                PluginSettingsStore settings,
                                InstalledPluginsStore installedStore,
                                SecureStorage secureStorage,
                                List<Path> inlinePluginDirs,
                                List<Path> inlinePluginDirsWithoutMcp) {
        this.directories = directories;
        this.settings = settings;
        this.installedStore = installedStore;
        this.secureStorage = secureStorage;
        this.inlinePluginDirs = inlinePluginDirs == null ? List.of()
            : inlinePluginDirs.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.inlinePluginDirsWithoutMcp = inlinePluginDirsWithoutMcp == null ? Set.of()
            : inlinePluginDirsWithoutMcp.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Compatibility shape; session IDs are now resolved at command invocation time. */
    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               Supplier<String> sessionIdSupplier) {
        this(directories, settings, installedStore);
    }

    /** Testable full shape; session IDs are resolved at command invocation time. */
    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               Supplier<String> sessionIdSupplier,
                               SecureStorage secureStorage) {
        this(directories, settings, installedStore, secureStorage);
    }

    /** Session-only {@code --plugin-dir} shape; session IDs remain invocation-time values. */
    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               Supplier<String> sessionIdSupplier,
                               List<Path> inlinePluginDirs) {
        this(directories, settings, installedStore,
            SecureStorageFactory.getInstance(), inlinePluginDirs, List.of());
    }

    /** Session-only plugin shape with MCP suppressed for selected roots. */
    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               Supplier<String> sessionIdSupplier,
                               List<Path> inlinePluginDirs,
                               List<Path> inlinePluginDirsWithoutMcp) {
        this(directories, settings, installedStore,
            SecureStorageFactory.getInstance(), inlinePluginDirs, inlinePluginDirsWithoutMcp);
    }

    /** Fully testable session-only plugin shape. */
    public PluginRuntimeLoader(PluginDirectories directories,
                               PluginSettingsStore settings,
                               InstalledPluginsStore installedStore,
                               Supplier<String> sessionIdSupplier,
                               SecureStorage secureStorage,
                               List<Path> inlinePluginDirs) {
        this(directories, settings, installedStore, secureStorage, inlinePluginDirs, List.of());
    }

    /** Loads all components of every enabled plugin. Never throws. */
    public PluginRuntimeSnapshot loadAll() {
        List<PluginCommandDefinition> commands = new ArrayList<>();
        List<BuiltInAgentDefinitions.AgentDefinition> agents = new ArrayList<>();
        List<PluginSkillDir> skillDirs = new ArrayList<>();
        List<OutputStyleConfig> outputStyles = new ArrayList<>();
        Map<HookEvent, List<HookMatcher>> hooks = new EnumMap<>(HookEvent.class);
        List<McpServerConfig> mcpServers = new ArrayList<>();
        Map<String, JsonNode> lspServers = new LinkedHashMap<>();
        List<WorkflowDefinition> workflows = new ArrayList<>();
        List<PluginError> errors = new ArrayList<>();
        int enabled = 0;
        int disabled = 0;
        Map<String, JsonNode> pluginSettings = new LinkedHashMap<>();

        Set<String> inlineNames = new LinkedHashSet<>();
        for (int index = 0; index < inlinePluginDirs.size(); index++) {
            Path root = inlinePluginDirs.get(index);
            String preManifestSource = "inline[" + index + "]";
            if (!Files.isDirectory(root)) {
                errors.add(new PluginError.PathNotFound(preManifestSource,
                    root.getFileName() != null ? root.getFileName().toString() : root.toString(),
                    root.toString(), PluginError.Component.COMMANDS));
                continue;
            }
            String fallbackName = root.getFileName() != null
                ? root.getFileName().toString() : "inline-" + index;
            PluginManifest manifest = loadManifest(
                preManifestSource, fallbackName, root, errors);
            if (manifest == null) continue;
            String name = StringUtils.isNotBlank(manifest.name())
                ? manifest.name() : fallbackName;
            String pluginId = name + "@inline";
            inlineNames.add(name);
            enabled++;
            mergePluginSettings(pluginSettings, root, manifest);
            loadPlugin(pluginId, name, root, manifest, true,
                !inlinePluginDirsWithoutMcp.contains(root.toAbsolutePath().normalize()),
                commands, agents, skillDirs, outputStyles,
                hooks, mcpServers, lspServers, workflows, errors);
        }

        Map<String, Boolean> enabledMap = settings.enabledPlugins();
        InstalledPlugins installed = installedStore.load();

        for (Map.Entry<String, List<InstalledPlugins.InstallationEntry>> entry
                : installed.plugins().entrySet()) {
            String pluginId = entry.getKey();
            if (!Boolean.TRUE.equals(enabledMap.get(pluginId))) {
                disabled++;
                continue;
            }
            Path root = resolveInstallPath(entry.getValue());
            String pluginName = PluginDirectories.PluginId.parse(pluginId).name();
            if (root == null) {
                String path = entry.getValue().isEmpty() ? "?"
                    : entry.getValue().getFirst().installPath();
                errors.add(new PluginError.PluginCacheMiss(pluginId, pluginName, path));
                continue;
            }
            if (inlineNames.contains(resolvePluginName(root, pluginName))) {
                continue;
            }
            enabled++;
            try {
                PluginManifest manifest = loadManifest(pluginId, pluginName, root, errors);
                if (manifest == null) continue;
                mergePluginSettings(pluginSettings, root, manifest);
                loadPlugin(pluginId, pluginName, root, manifest, false, true,
                    commands, agents, skillDirs, outputStyles,
                    hooks, mcpServers, lspServers, workflows, errors);
            } catch (Exception e) {
                LOG.warn("Failed to load plugin {}: {}", pluginId, e.getMessage());
                errors.add(new PluginError.GenericError(pluginId, pluginName,
                    "Failed to load plugin: " + e.getMessage()));
            }
        }

        ObjectNode mergedPluginSettings = JsonUtils.getMapper().createObjectNode();
        pluginSettings.forEach(mergedPluginSettings::set);
        SettingsSources.setPluginSettingsBase(mergedPluginSettings);

        return new PluginRuntimeSnapshot(
            commands, agents, skillDirs, outputStyles,
            hooks, mcpServers, lspServers, workflows, errors, enabled, disabled);
    }


    private static void mergePluginSettings(Map<String, JsonNode> merged,
                                             Path pluginRoot,
                                             PluginManifest manifest) {
        JsonNode candidate = null;
        Path settingsPath = pluginRoot.resolve("settings.json");
        if (Files.isRegularFile(settingsPath)) {
            try {
                JsonNode parsed = JsonUtils.readJson(settingsPath);
                if (parsed != null && parsed.isObject()) candidate = parsed;
            } catch (IOException | RuntimeException e) {
                LOG.debug("Failed to parse plugin settings {}: {}", settingsPath, e.getMessage());
            }
        }
        if (candidate == null && manifest.settings() != null && manifest.settings().isObject()) {
            candidate = manifest.settings();
        }
        if (candidate == null) return;
        JsonNode agent = candidate.get("agent");
        if (agent != null && agent.isTextual()) merged.put("agent", agent.deepCopy());
    }

    // ── per-plugin orchestration ─────────────────────────────────────────────

    private void loadPlugin(String pluginId, String pluginName, Path root,
                            PluginManifest manifest, boolean inline, boolean includeMcp,
                            List<PluginCommandDefinition> commands,
                            List<BuiltInAgentDefinitions.AgentDefinition> agents,
                            List<PluginSkillDir> skillDirs,
                            List<OutputStyleConfig> outputStyles,
                            Map<HookEvent, List<HookMatcher>> hooks,
                            List<McpServerConfig> mcpServers,
                            Map<String, JsonNode> lspServers,
                            List<WorkflowDefinition> workflows,
                            List<PluginError> errors) {
        String name = StringUtils.isNotBlank(manifest.name())
            ? manifest.name() : pluginName;
        PluginContext ctx = new PluginContext(pluginId, name, root,
            directories.pluginDataDir(pluginId), manifest,
            loadUserConfigValues(pluginId),
            loadChannelUserConfigValues(pluginId, manifest.channels()),
            manifest.userConfig() != null ? manifest.userConfig() : Map.of(),
            inline ? name + "@inline" : pluginId,
            root.toAbsolutePath().normalize().toString());

        loadCommands(ctx, commands, errors);
        loadAgents(ctx, agents, errors);
        collectSkillDirs(ctx, skillDirs, errors);
        loadOutputStyles(ctx, outputStyles, errors);
        loadHooks(ctx, hooks, errors);
        if (includeMcp) loadMcpServers(ctx, mcpServers, errors);
        loadLspServers(ctx, lspServers, errors);
        loadWorkflows(ctx, workflows);
    }

    /** Immutable per-plugin context threaded through the component loaders. */
    private record PluginContext(
        String pluginId,
        String pluginName,
        Path root,
        Path dataDir,
        PluginManifest manifest,
        Map<String, String> userConfig,
        Map<String, Map<String, String>> channelUserConfig,
        Map<String, UserConfigOption> userConfigSchema,
        String source,
        String loadedFrom) {

        String substituteContent(String content) {
            return PluginVariables.substitute(
                content, root, dataDir, userConfig, userConfigSchema, null);
        }

        /** Non-prose variant for hook/MCP values: full user_config, no session id. */
        String substituteConfigValue(String value) {
            String out = PluginVariables.substitutePluginPaths(value, root, dataDir);
            return PluginVariables.substituteUserConfigInContent(out, userConfig, null);
        }


        Map<String, String> mcpUserConfig(String serverName) {
            Map<String, String> channel = channelUserConfig.get(serverName);
            if (channel == null || channel.isEmpty()) return userConfig;
            Map<String, String> merged = new LinkedHashMap<>(userConfig);
            merged.putAll(channel);
            return Map.copyOf(merged);
        }
    }

    private Map<String, Map<String, String>> loadChannelUserConfigValues(
            String pluginId, List<PluginChannel> channels) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (PluginChannel channel : channels) {
            if (channel.server() == null || channel.userConfig().isEmpty()) continue;
            Map<String, String> values = new LinkedHashMap<>();
            loadMcpServerUserConfig(pluginId, channel.server())
                .forEach((key, value) -> values.put(key, String.valueOf(value)));
            if (!values.isEmpty()) result.put(channel.server(), Map.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private PluginManifest loadManifest(String pluginId, String pluginName, Path root,
                                        List<PluginError> errors) {
        Path manifestPath = root.resolve(".claude-plugin").resolve("plugin.json");
        if (!Files.isRegularFile(manifestPath)) {
// Minimal plugin without a manifest — defaults.
            return PluginManifest.builder(pluginName).build();
        }
        try {
            return JsonUtils.getMapper().readValue(manifestPath.toFile(), PluginManifest.class);
        } catch (Exception e) {
            errors.add(new PluginError.ManifestParseError(
                pluginId, pluginName, manifestPath.toString(), e.getMessage()));
            return null;
        }
    }

    private String resolvePluginName(Path root, String fallbackName) {
        Path manifestPath = root.resolve(".claude-plugin").resolve("plugin.json");
        if (!Files.isRegularFile(manifestPath)) return fallbackName;
        try {
            PluginManifest manifest = JsonUtils.getMapper()
                .readValue(manifestPath.toFile(), PluginManifest.class);
            return StringUtils.isNotBlank(manifest.name())
                ? manifest.name() : fallbackName;
        } catch (Exception _) {
            return fallbackName;
        }
    }

    private static Path resolveInstallPath(List<InstalledPlugins.InstallationEntry> installations) {
        for (InstalledPlugins.InstallationEntry entry : installations) {
            if (entry.installPath() != null) {
                Path p = Path.of(entry.installPath());
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Merges settings options with keychain-backed secrets; secure values win. */
    private Map<String, String> loadUserConfigValues(String pluginId) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode config = settings.pluginConfig(pluginId);
        JsonNode options = config != null ? config.get("options") : null;
        if (options != null && options.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = options.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                values.put(field.getKey(), stringifyOption(field.getValue()));
            }
        }
        if (secureStorage != null) {
            secureStorage.read().map(SecureStorageData::pluginSecrets)
                .map(secrets -> secrets.get(pluginId))
                .ifPresent(values::putAll);
        }
        return values;
    }

    private static String stringifyOption(JsonNode value) {
        if (value.isArray()) {
            List<String> parts = new ArrayList<>();
            value.forEach(item -> parts.add(item.asText()));
            return String.join(",", parts);
        }
        return value.asText();
    }

    // ── workflows ───────────────────────────────────────────────────────────

    private void loadWorkflows(PluginContext ctx, List<WorkflowDefinition> workflows) {
        Set<Path> loaded = new HashSet<>();
        loadWorkflowPath(ctx, ctx.root().resolve("workflows"), loaded, workflows);
        for (String relative : ctx.manifest().workflowPaths()) {
            loadWorkflowPath(ctx, ctx.root().resolve(relative), loaded, workflows);
        }
    }

    private void loadWorkflowPath(PluginContext ctx, Path candidate, Set<Path> loaded,
                                  List<WorkflowDefinition> workflows) {
        Path root = ctx.root().toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            LOG.warn("Plugin workflow path escapes plugin root: {}", candidate);
            return;
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.list(path)) {
                files.filter(Files::isRegularFile)
                    .filter(file -> Strings.CS.endsWith(file.getFileName().toString(), ".js"))
                    .sorted()
                    .forEach(file -> loadWorkflowFile(ctx, file, root, loaded, workflows));
            } catch (IOException e) {
                LOG.warn("Failed to list plugin workflows {}: {}", path, e.getMessage());
            }
        } else if (Files.isRegularFile(path) && Strings.CS.endsWith(path.getFileName().toString(), ".js")) {
            loadWorkflowFile(ctx, path, root, loaded, workflows);
        }
    }

    private void loadWorkflowFile(PluginContext ctx, Path file, Path root,
                                  Set<Path> loaded,
                                  List<WorkflowDefinition> workflows) {
        try {
            Path realRoot = root.toRealPath();
            Path real = file.toRealPath();
            if (!real.startsWith(realRoot) || !loaded.add(real)
                    || Files.size(real) > WorkflowScriptParser.MAX_SCRIPT_BYTES) return;
            String script = Files.readString(real);
            ParsedWorkflowScript parsed = WorkflowScriptParser.parse(script);
            WorkflowMetadata metadata = parsed.metadata();
            WorkflowMetadata namespaced = new WorkflowMetadata(
                ctx.pluginName() + ":" + metadata.name(), metadata.title(),
                metadata.description(), metadata.whenToUse(), metadata.phases());
            workflows.add(new WorkflowDefinition(namespaced, script, parsed.body(),
                WorkflowSource.PLUGIN, real, ctx.pluginName(), false, false));
        } catch (Exception e) {
            LOG.warn("Plugin workflow {} is invalid and was skipped: {}", file, e.getMessage());
        }
    }

    // ── commands ─────────────────────────────────────────────────────────────

    private void loadCommands(PluginContext ctx, List<PluginCommandDefinition> commands,
                              List<PluginError> errors) {
        Set<Path> loadedPaths = new HashSet<>();

        // Default commands/ directory — only when the manifest doesn't override.
        Path defaultDir = ctx.root().resolve("commands");
        if (ctx.manifest().commands() == null && Files.isDirectory(defaultDir)) {
            walkCommandDir(ctx, defaultDir, loadedPaths, commands, errors);
        }

        JsonNode manifestCommands = ctx.manifest().commands();
        if (manifestCommands == null || manifestCommands.isNull()) {
            return;
        }
        if (isCommandMetadataMapping(manifestCommands)) {
            loadCommandsFromMetadata(ctx, (ObjectNode) manifestCommands,
                loadedPaths, commands, errors);
            return;
        }
        // Path or array-of-paths format.
        for (String rel : ctx.manifest().commandPaths()) {
            Path p = ctx.root().resolve(rel);
            if (Files.isDirectory(p)) {
                walkCommandDir(ctx, p, loadedPaths, commands, errors);
            } else if (Files.isRegularFile(p) && Strings.CS.endsWith(rel, ".md")) {
                String name = ctx.pluginName() + ":" + FileUtils.stripSuffix(p.getFileName().toString(), ".md");
                loadCommandFile(ctx, p, name, false, null, null, loadedPaths, commands, errors);
            } else {
                errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                    p.toString(), PluginError.Component.COMMANDS));
            }
        }
    }


    private static boolean isCommandMetadataMapping(JsonNode commands) {
        if (!commands.isObject() || commands.isEmpty()) {
            return false;
        }
        JsonNode first = commands.iterator().next();
        return first.isObject() && (first.has("source") || first.has("content"));
    }

    private void loadCommandsFromMetadata(PluginContext ctx, ObjectNode mapping,
                                          Set<Path> loadedPaths,
                                          List<PluginCommandDefinition> commands,
                                          List<PluginError> errors) {
        Iterator<Map.Entry<String, JsonNode>> fields = mapping.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode meta = field.getValue();
            if (!meta.isObject()) {
                continue;
            }
            String name = ctx.pluginName() + ":" + field.getKey();
            String descriptionOverride = meta.path("description").isTextual()
                ? meta.get("description").asText() : null;
            String hintOverride = meta.path("argumentHint").isTextual()
                ? meta.get("argumentHint").asText() : null;
            if (meta.path("source").isTextual()) {
                Path p = ctx.root().resolve(meta.get("source").asText());
                if (Files.isRegularFile(p)) {
                    loadCommandFile(ctx, p, name, false,
                        descriptionOverride, hintOverride, loadedPaths, commands, errors);
                } else {
                    errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                        p.toString(), PluginError.Component.COMMANDS));
                }
            } else if (meta.path("content").isTextual()) {
                commands.add(buildCommand(ctx, name,
                    meta.get("content").asText(), false, descriptionOverride, hintOverride));
            }
        }
    }

    /**
     * Recursive walk over a commands directory.
     */
    private void walkCommandDir(PluginContext ctx, Path dir, Set<Path> loadedPaths,
                                List<PluginCommandDefinition> commands, List<PluginError> errors) {
        scanCommandDir(ctx, dir, List.of(), loadedPaths, commands, errors);
    }

    private void scanCommandDir(PluginContext ctx, Path dir, List<String> namespace,
                                Set<Path> loadedPaths,
                                List<PluginCommandDefinition> commands, List<PluginError> errors) {
        Path skillFile = findSkillFile(dir);
        if (skillFile != null) {
            String baseName = namespace.isEmpty()
                ? dir.getFileName().toString()
                : String.join(":", namespace);
            loadCommandFile(ctx, skillFile, ctx.pluginName() + ":" + baseName, true,
                null, null, loadedPaths, commands, errors);
            return;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                String fileName = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    List<String> childNs = new ArrayList<>(namespace);
                    childNs.add(fileName);
                    scanCommandDir(ctx, entry, childNs, loadedPaths, commands, errors);
                } else if (Strings.CI.endsWith(fileName, ".md") && Files.isRegularFile(entry)) {
                    List<String> parts = new ArrayList<>(namespace);
                    parts.add(FileUtils.stripSuffix(fileName, ".md"));
                    loadCommandFile(ctx, entry, ctx.pluginName() + ":" + String.join(":", parts),
                        false, null, null, loadedPaths, commands, errors);
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to scan commands directory {}: {}", dir, e.getMessage());
        }
    }

    private static Path findSkillFile(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                .filter(p -> Strings.CI.equals(p.getFileName().toString(), "SKILL.md"))
                .filter(Files::isRegularFile)
                .findFirst().orElse(null);
        } catch (IOException _) {
            return null;
        }
    }

    private void loadCommandFile(PluginContext ctx, Path file, String commandName,
                                 boolean isSkill, String descriptionOverride,
                                 String hintOverride, Set<Path> loadedPaths,
                                 List<PluginCommandDefinition> commands, List<PluginError> errors) {
        if (FileUtils.isDuplicatePath(file, loadedPaths)) {
            return;
        }
        try {
            commands.add(buildCommand(ctx, commandName,
                Files.readString(file), isSkill, descriptionOverride, hintOverride));
        } catch (Exception e) {
            errors.add(new PluginError.ComponentLoadFailed(ctx.pluginId(), ctx.pluginName(),
                PluginError.Component.COMMANDS, file.toString(), e.getMessage()));
        }
    }

    private PluginCommandDefinition buildCommand(PluginContext ctx, String commandName, String raw,
                                          boolean isSkill, String descriptionOverride,
                                          String hintOverride) {
        FrontmatterParser.ParseResult parsed = frontmatter.parse(raw);
        Map<String, Object> fm = parsed.metadata();

        String frontmatterDescription = FrontmatterParser.coerceDescriptionToString(fm.get("description"));
        boolean hasUserSpecifiedDescription = descriptionOverride != null
            || (StringUtils.isNotBlank(frontmatterDescription));
        String description = descriptionOverride != null ? descriptionOverride
            : frontmatterDescription;
        if (StringUtils.isBlank(description)) {
            description = extractDescriptionFromMarkdown(parsed.body(),
                isSkill ? "Plugin skill" : "Plugin command");
        }
        String argumentHint = hintOverride != null ? hintOverride
            : fmString(fm, "argument-hint");
        List<String> argNames = ArgumentSubstitutor.parseArgumentNames(fm.get("arguments"));
        Object userInvocableValue = fm.get("user-invocable");
        boolean userInvocable = userInvocableValue == null
            || FrontmatterParser.parseBooleanFrontmatter(userInvocableValue);
        boolean hidden = !userInvocable;

        String prompt = ctx.substituteContent(parsed.body());
        List<String> allowedTools = parseAllowedTools(fm.get("allowed-tools"), ctx);
        String model = fmString(fm, "model");
        if (model != null && Strings.CI.equals(model, "inherit")) model = null;
        String effort = fmString(fm, "effort");
        String shell = FrontmatterParser.parseShellFrontmatter(fm.get("shell"));
        boolean disableModelInvocation = FrontmatterParser.parseBooleanFrontmatter(
            fm.get("disable-model-invocation"));
        String userFacingName = fmString(fm, "name");
        String whenToUse = firstNonBlank(fmString(fm, "when_to_use"), fmString(fm, "when-to-use"));
        String version = fmString(fm, "version");
        String progressMessage = isSkill ? "loading" : "running";
        return PluginCommandDefinition.builder(commandName, prompt, ctx.pluginName())
            .description(description)
            .argumentHint(argumentHint)
            .argNames(argNames)
            .hidden(hidden)
            .allowedTools(allowedTools)
            .model(model)
            .effort(effort)
            .disableModelInvocation(disableModelInvocation)
            .userFacingName(userFacingName)
            .whenToUse(whenToUse)
            .version(version)
            .progressMessage(progressMessage)
            .contentLength(parsed.body().length())
            .source(ctx.source())
            .loadedFrom(ctx.loadedFrom() != null
                ? ctx.loadedFrom() : (isSkill ? "plugin" : null))
            .hasUserSpecifiedDescription(hasUserSpecifiedDescription)
            .shell(shell)
            .build();
    }

    private static List<String> parseAllowedTools(Object raw, PluginContext ctx) {
        if (raw == null) return List.of();
        List<String> values;
        if (raw instanceof List<?> list) {
            values = list.stream().map(String::valueOf).toList();
        } else {
            String text = unquoteFrontmatterScalar(String.valueOf(raw).trim());
            if (Strings.CS.startsWith(text, "[") && Strings.CS.endsWith(text, "]")) {
                text = text.substring(1, text.length() - 1);
            }
            values = Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        }
        return values.stream()
            .map(value -> PluginVariables.substitutePluginPaths(
                unquoteFrontmatterScalar(value.trim()), ctx.root(), ctx.dataDir()))
            .filter(value -> !StringUtils.isBlank(value))
            .toList();
    }


    static String extractDescriptionFromMarkdown(String content, String defaultDescription) {
        if (content != null) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    String text = trimmed.replaceFirst("^#+\\s+", "");
                    return text.length() > 100 ? text.substring(0, 97) + "..." : text;
                }
            }
        }
        return defaultDescription;
    }

    // ── agents ───────────────────────────────────────────────────────────────

    private void loadAgents(PluginContext ctx,
                            List<BuiltInAgentDefinitions.AgentDefinition> agents,
                            List<PluginError> errors) {
        Set<Path> loadedPaths = new HashSet<>();

        Path defaultDir = ctx.root().resolve("agents");
        if (ctx.manifest().agents() == null && Files.isDirectory(defaultDir)) {
            walkAgentDir(ctx, defaultDir, List.of(), loadedPaths, agents, errors);
        }
        for (String rel : ctx.manifest().agentPaths()) {
            Path p = ctx.root().resolve(rel);
            if (Files.isDirectory(p)) {
                walkAgentDir(ctx, p, List.of(), loadedPaths, agents, errors);
            } else if (Files.isRegularFile(p) && Strings.CS.endsWith(rel, ".md")) {
                loadAgentFile(ctx, p, List.of(), loadedPaths, agents, errors);
            } else {
                errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                    p.toString(), PluginError.Component.AGENTS));
            }
        }
    }

    private void walkAgentDir(PluginContext ctx, Path dir, List<String> namespace,
                              Set<Path> loadedPaths,
                              List<BuiltInAgentDefinitions.AgentDefinition> agents,
                              List<PluginError> errors) {
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                String fileName = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    List<String> childNs = new ArrayList<>(namespace);
                    childNs.add(fileName);
                    walkAgentDir(ctx, entry, childNs, loadedPaths, agents, errors);
                } else if (Strings.CI.endsWith(fileName, ".md") && Files.isRegularFile(entry)) {
                    loadAgentFile(ctx, entry, namespace, loadedPaths, agents, errors);
                }
            }
        } catch (IOException e) {
            LOG.debug("Failed to scan agents directory {}: {}", dir, e.getMessage());
        }
    }

    private void loadAgentFile(PluginContext ctx, Path file, List<String> namespace,
                               Set<Path> loadedPaths,
                               List<BuiltInAgentDefinitions.AgentDefinition> agents,
                               List<PluginError> errors) {
        if (FileUtils.isDuplicatePath(file, loadedPaths)) {
            return;
        }
        try {
            FrontmatterParser.ParseResult parsed = frontmatter.parse(Files.readString(file));
            Map<String, Object> fm = parsed.metadata();

            String baseName = fmString(fm, "name");
            if (StringUtils.isBlank(baseName)) {
                baseName = FileUtils.stripSuffix(file.getFileName().toString(), ".md");
            }
            List<String> nameParts = new ArrayList<>();
            nameParts.add(ctx.pluginName());
            nameParts.addAll(namespace);
            nameParts.add(baseName);
            String agentType = String.join(":", nameParts);

            String whenToUse = firstNonBlank(
                fmString(fm, "description"),
                fmString(fm, "when-to-use"));
            if (whenToUse == null) {
                whenToUse = "Agent from " + ctx.pluginName() + " plugin";
            }

            // permissionMode / hooks / mcpServers are intentionally IGNORED for
            // plugin agents — third-party marketplace code must not escalate

            for (String field : List.of("permissionMode", "hooks", "mcpServers")) {
                if (fm.containsKey(field)) {
                    LOG.debug("Plugin agent {} sets {}, ignored for plugin agents", file, field);
                }
            }

            List<String> tools = parseToolList(fm.get("tools"));
            List<String> disallowedTools = parseToolList(fm.get("disallowedTools"));
            String color = fmString(fm, "color");
            String model = normalizeModel(fmString(fm, "model"));
            String memory = normalizeMemoryScope(fmString(fm, "memory"));
            Object maxTurnsRaw = fm.get("maxTurns");
            Integer maxTurns = FrontmatterParser.parsePositiveIntFromFrontmatter(maxTurnsRaw);
            if (fm.containsKey("maxTurns") && maxTurns == null) {
                LOG.debug("Plugin agent file {} has invalid maxTurns '{}'. Must be a positive integer.",
                    file, maxTurnsRaw);
            }

            String systemPrompt = StringUtils.isBlank(parsed.body())
                ? null
                : PluginVariables.substituteUserConfigInContent(
                    PluginVariables.substitutePluginPaths(
                        parsed.body().trim(), ctx.root(), ctx.dataDir()),
                    ctx.userConfig(), ctx.userConfigSchema());

            agents.add(BuiltInAgentDefinitions.AgentDefinition
                .builder(agentType, whenToUse.replace("\\n", "\n"))
                .tools(tools).disallowedTools(disallowedTools).color(color)
                .memory(memory).model(model).systemPrompt(systemPrompt)
                .source(AgentSource.PLUGIN).filePath(file).maxTurns(maxTurns)
                .build());
        } catch (Exception e) {
            errors.add(new PluginError.ComponentLoadFailed(ctx.pluginId(), ctx.pluginName(),
                PluginError.Component.AGENTS, file.toString(), e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseToolList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return (List<String>) list;
        }
        String s = raw.toString().trim();
        if (s.isEmpty()) {
            return List.of();
        }
        if (Strings.CS.startsWith(s, "[") && Strings.CS.endsWith(s, "]")) {
            s = s.substring(1, s.length() - 1);
        }
        return Arrays.stream(s.split(","))
            .map(String::trim)
            .filter(t -> !t.isEmpty())
            .toList();
    }

    private static String normalizeModel(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        return Strings.CI.equals(trimmed, "inherit") ? "inherit" : trimmed;
    }

    private static String normalizeMemoryScope(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "user", "project", "local" -> v;
            default -> null;
        };
    }

    // ── skills ───────────────────────────────────────────────────────────────

    private void collectSkillDirs(PluginContext ctx, List<PluginSkillDir> skillDirs,
                                  List<PluginError> errors) {
        Path defaultDir = ctx.root().resolve("skills");
        if (ctx.manifest().skills() == null) {
            if (Files.isDirectory(defaultDir)) {
                skillDirs.add(new PluginSkillDir(ctx.pluginName(), defaultDir));
            } else if (Files.isRegularFile(ctx.root().resolve("SKILL.md"))) {

                // itself for SKILL.md before scanning child directories. Some
                // marketplace plugins are cached exactly in that direct form
                // (the cache root is the skill directory, with no local
// on), so defaulting only to root/skills
                // silently drops an otherwise enabled skill.
                skillDirs.add(new PluginSkillDir(
                    ctx.pluginName(), ctx.root(), ctx.pluginName()));
            }
        }
        for (String rel : ctx.manifest().skillPaths()) {
            Path p = ctx.root().resolve(rel);
            if (Files.isDirectory(p)) {
                skillDirs.add(new PluginSkillDir(ctx.pluginName(), p));
            } else {
                errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                    p.toString(), PluginError.Component.SKILLS));
            }
        }
    }

    // ── output styles ───────────────────────────────────────────────────────

    private void loadOutputStyles(PluginContext ctx, List<OutputStyleConfig> styles,
                                  List<PluginError> errors) {
        Set<Path> loadedPaths = new HashSet<>();


        // in addition to any manifest-declared extra paths.
        Path defaultDir = ctx.root().resolve("output-styles");
        if (Files.isDirectory(defaultDir)) {
            walkOutputStyleDir(ctx, defaultDir, loadedPaths, styles, errors);
        }
        for (String rel : ctx.manifest().outputStylePaths()) {
            Path path = ctx.root().resolve(rel);
            if (Files.isDirectory(path)) {
                walkOutputStyleDir(ctx, path, loadedPaths, styles, errors);
            } else if (Files.isRegularFile(path)
                    && Strings.CI.endsWith(path.getFileName().toString(), ".md")) {
                loadOutputStyleFile(ctx, path, loadedPaths, styles, errors);
            } else {
                errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                    path.toString(), PluginError.Component.OUTPUT_STYLES));
            }
        }
    }

    private void walkOutputStyleDir(PluginContext ctx, Path dir, Set<Path> loadedPaths,
                                    List<OutputStyleConfig> styles,
                                    List<PluginError> errors) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> Strings.CI.endsWith(path.getFileName().toString(), ".md"))
                    .sorted().toList()) {
                loadOutputStyleFile(ctx, file, loadedPaths, styles, errors);
            }
        } catch (IOException e) {
            LOG.debug("Failed to scan plugin output styles {}: {}", dir, e.getMessage());
        }
    }

    private void loadOutputStyleFile(PluginContext ctx, Path file,
                                     Set<Path> loadedPaths,
                                     List<OutputStyleConfig> styles,
                                     List<PluginError> errors) {
        if (FileUtils.isDuplicatePath(file, loadedPaths)) return;
        try {
            FrontmatterParser.ParseResult parsed = frontmatter.parse(Files.readString(file));
            Map<String, Object> fm = parsed.metadata();
            String baseName = fmString(fm, "name");
            if (StringUtils.isBlank(baseName)) {
                baseName = FileUtils.stripSuffix(file.getFileName().toString(), ".md");
            }
            String name = ctx.pluginName() + ":" + baseName;
            String description = scalarDescription(fm.get("description"));
            if (description == null) {
                description = extractDescriptionFromMarkdown(
                    parsed.body(), "Output style from " + ctx.pluginName() + " plugin");
            }
            boolean forced = FrontmatterParser.parseBooleanFrontmatter(fm.get("force-for-plugin"));
            styles.add(new OutputStyleConfig(
                name, description, parsed.body() == null ? "" : parsed.body().trim(),
                false, OutputStyleConfig.Source.PLUGIN, forced));
        } catch (Exception e) {
            errors.add(new PluginError.ComponentLoadFailed(
                ctx.pluginId(), ctx.pluginName(), PluginError.Component.OUTPUT_STYLES,
                file.toString(), e.getMessage()));
        }
    }

    private static String scalarDescription(Object value) {
        if (value == null) return null;
        if (value instanceof String string) {
            String trimmed = unquoteFrontmatterScalar(string).trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    // ── hooks ────────────────────────────────────────────────────────────────

    private void loadHooks(PluginContext ctx, Map<HookEvent, List<HookMatcher>> hooks,
                           List<PluginError> errors) {
        Set<Path> loadedHookPaths = new LinkedHashSet<>();

        Path standard = ctx.root().resolve("hooks").resolve("hooks.json");
        if (Files.isRegularFile(standard)) {
            HooksSettings parsed = parseHooksFile(ctx, standard, errors);
            if (parsed != null) {
                mergeHooks(hooks, parsed);
                loadedHookPaths.add(normalize(standard));
            }
        }

        JsonNode manifestHooks = ctx.manifest().hooks();
        if (manifestHooks == null || manifestHooks.isNull()) {
            return;
        }
        List<JsonNode> specs = manifestHooks.isArray()
            ? streamToList(manifestHooks)
            : List.of(manifestHooks);
        for (JsonNode spec : specs) {
            if (spec.isTextual()) {
                Path hookFile = ctx.root().resolve(spec.asText());
                if (!Files.isRegularFile(hookFile)) {
                    errors.add(new PluginError.PathNotFound(ctx.pluginId(), ctx.pluginName(),
                        hookFile.toString(), PluginError.Component.HOOKS));
                    continue;
                }
                Path normalized = normalize(hookFile);
                if (loadedHookPaths.contains(normalized)) {
                    errors.add(new PluginError.HookLoadFailed(ctx.pluginId(), ctx.pluginName(),
                        hookFile.toString(),
                        "Duplicate hooks file detected: " + spec.asText()
                            + " resolves to already-loaded file " + normalized
                            + ". The standard hooks/hooks.json is loaded automatically, so "
                            + "manifest.hooks should only reference additional hook files."));
                    continue;
                }
                HooksSettings parsed = parseHooksFile(ctx, hookFile, errors);
                if (parsed != null) {
                    mergeHooks(hooks, parsed);
                    loadedHookPaths.add(normalized);
                }
            } else if (spec.isObject()) {
                // Inline hooks — already HooksSettings shape (no wrapper).
                mergeHooks(hooks, HooksSettings.fromJson(substituteInTree(ctx, spec)));
            }
        }
    }

    /** Parses a hooks JSON file: {@code {description, hooks:{...}}} wrapper or bare shape. */
    private HooksSettings parseHooksFile(PluginContext ctx, Path file, List<PluginError> errors) {
        try {
            JsonNode root = JsonUtils.readJson(file);
            if (root == null || !root.isObject()) {
                throw new IOException("hooks file is not a JSON object");
            }
            JsonNode hooksNode = root.has("hooks") && root.get("hooks").isObject()
                ? root.get("hooks") : root;
            return HooksSettings.fromJson(substituteInTree(ctx, hooksNode));
        } catch (Exception e) {
            errors.add(new PluginError.HookLoadFailed(ctx.pluginId(), ctx.pluginName(),
                file.toString(), e.getMessage()));
            return null;
        }
    }

    /**
     * Substitutes plugin variables in every textual value of a JSON tree.
     */
    private JsonNode substituteInTree(PluginContext ctx, JsonNode node) {
        JsonNode copy = node.deepCopy();
        substituteInPlace(ctx, copy);
        return copy;
    }

    private void substituteInPlace(PluginContext ctx, JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = obj.get(name);
                if (child.isTextual()) {
                    obj.put(name, ctx.substituteConfigValue(child.asText()));
                } else {
                    substituteInPlace(ctx, child);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode child = node.get(i);
                if (child.isTextual()) {
                    ((ArrayNode) node)
                        .set(i, JsonUtils.getMapper().getNodeFactory()
                            .textNode(ctx.substituteConfigValue(child.asText())));
                } else {
                    substituteInPlace(ctx, child);
                }
            }
        }
    }

    private static void mergeHooks(Map<HookEvent, List<HookMatcher>> target, HooksSettings source) {
        for (Map.Entry<HookEvent, List<HookMatcher>> entry : source.eventHooks().entrySet()) {
            target.computeIfAbsent(entry.getKey(), _ -> new ArrayList<>())
                .addAll(entry.getValue());
        }
    }

    // ── MCP servers ──────────────────────────────────────────────────────────

    private void loadMcpServers(PluginContext ctx, List<McpServerConfig> mcpServers,
                                List<PluginError> errors) {
        Map<String, JsonNode> raw = new LinkedHashMap<>();


        collectMcpServersFromFile(ctx, ctx.root().resolve(".mcp.json"), raw, errors);

        JsonNode spec = ctx.manifest().mcpServers();
        if (spec != null && !spec.isNull()) {
            if (spec.isTextual()) {
                collectMcpServersFromSpec(ctx, spec.asText(), raw, errors);
            } else if (spec.isArray()) {
                for (JsonNode item : spec) {
                    if (item.isTextual()) {
                        collectMcpServersFromSpec(ctx, item.asText(), raw, errors);
                    } else if (item.isObject()) {
                        item.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
                    }
                }
            } else if (spec.isObject()) {
                spec.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
            }
        }

        for (Map.Entry<String, JsonNode> entry : raw.entrySet()) {
            try {
                McpServerConfig config = buildMcpConfig(ctx, entry.getKey(), entry.getValue(), errors);
                if (config != null) {
                    mcpServers.add(config);
                }
            } catch (Exception e) {
                errors.add(new PluginError.GenericError(entry.getKey(), ctx.pluginName(),
                    e.getMessage()));
            }
        }
    }

    // ── LSP servers ─────────────────────────────────────────────────────────



    // lspServers field (string path / array of paths+objects / object); each
    // entry is scope-prefixed plugin:<name>:<server>, plugin variables and
    // user_config are substituted, and CLAUDE_PLUGIN_ROOT/CLAUDE_PLUGIN_DATA
    // are injected. The produced config nodes are JSON (env already resolved)
    // so the lsp module can parse them without a services dependency.

    private void loadLspServers(PluginContext ctx, Map<String, JsonNode> lspServers,
                                List<PluginError> errors) {
        Map<String, JsonNode> raw = new LinkedHashMap<>();


        collectLspServersFromFile(ctx, ctx.root().resolve(".lsp.json"), raw, errors);

        JsonNode spec = ctx.manifest().lspServers();
        if (spec != null && !spec.isNull()) {
            if (spec.isTextual()) {
                collectLspServersFromSpec(ctx, spec.asText(), raw, errors);
            } else if (spec.isArray()) {
                for (JsonNode item : spec) {
                    if (item.isTextual()) {
                        collectLspServersFromSpec(ctx, item.asText(), raw, errors);
                    } else if (item.isObject()) {
                        item.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
                    }
                }
            } else if (spec.isObject()) {
                spec.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
            }
        }

        for (Map.Entry<String, JsonNode> entry : raw.entrySet()) {
            try {
                JsonNode resolved = resolveLspConfig(ctx, entry.getValue());
                lspServers.put("plugin:" + ctx.pluginName() + ":" + entry.getKey(), resolved);
            } catch (Exception e) {
                errors.add(new PluginError.LspConfigInvalid(ctx.pluginId(), ctx.pluginName(),
                    entry.getKey(), e.getMessage()));
            }
        }
    }

    private void collectLspServersFromSpec(PluginContext ctx, String rel,
                                           Map<String, JsonNode> raw, List<PluginError> errors) {
        // Guard against path traversal: the resolved file must stay inside the plugin dir.
        Path base = ctx.root().toAbsolutePath().normalize();
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base)) {
            errors.add(new PluginError.LspConfigInvalid(ctx.pluginId(), ctx.pluginName(),
                rel, "Invalid path: must be relative and within the plugin directory"));
            return;
        }
        collectLspServersFromFile(ctx, resolved, raw, errors);
    }


    private void collectLspServersFromFile(PluginContext ctx, Path file,
                                           Map<String, JsonNode> raw, List<PluginError> errors) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JsonUtils.readJson(file);
            JsonNode servers = root.has("lspServers") && root.get("lspServers").isObject()
                ? root.get("lspServers") : root;
            if (servers.isObject()) {
                servers.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
            }
        } catch (Exception e) {
            errors.add(new PluginError.LspConfigInvalid(ctx.pluginId(), ctx.pluginName(),
                file.toString(), "Failed to read/parse LSP config: " + e.getMessage()));
        }
    }


    private JsonNode resolveLspConfig(PluginContext ctx, JsonNode node) {
        List<String> missingVars = new ArrayList<>();
        ObjectNode resolved = (ObjectNode) node.deepCopy();

        if (resolved.has("command") && !resolved.get("command").isNull()) {
            resolved.put("command", resolveLspValue(ctx, resolved.get("command").asText(), missingVars));
        }
        if (resolved.has("args") && resolved.get("args").isArray()) {
            ArrayNode arr = (ArrayNode) resolved.get("args");
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, JsonUtils.getMapper().getNodeFactory()
                    .textNode(resolveLspValue(ctx, arr.get(i).asText(), missingVars)));
            }
        }
        if (resolved.has("workspaceFolder") && !resolved.get("workspaceFolder").isNull()) {
            resolved.put("workspaceFolder",
                resolveLspValue(ctx, resolved.get("workspaceFolder").asText(), missingVars));
        }

// CLAUDE_PLUGIN_ROOT / CLAUDE_PLUGIN_DATA are injected first and cannot be overridden by a
// declared env var.
        ObjectNode env = resolved.has("env") && resolved.get("env").isObject()
            ? (ObjectNode) resolved.get("env") : resolved.putObject("env");
        env.put("CLAUDE_PLUGIN_ROOT", ctx.root().toString());
        env.put("CLAUDE_PLUGIN_DATA", ctx.dataDir().toString());
        List<String> envKeys = new ArrayList<>();
        env.fieldNames().forEachRemaining(envKeys::add);
        for (String key : envKeys) {
            if (Strings.CS.equals("CLAUDE_PLUGIN_ROOT", key) || Strings.CS.equals("CLAUDE_PLUGIN_DATA", key)) {
                continue;
            }
            env.put(key, resolveLspValue(ctx, env.get(key).asText(), missingVars));
        }

        if (!missingVars.isEmpty()) {
            LOG.debug("Plugin {} LSP config references missing env vars: {}", ctx.pluginName(),
                String.join(", ", new LinkedHashSet<>(missingVars)));
        }
        return resolved;
    }

    /**
     * Plugin vars → user_config → env vars, matching {@link #resolveMcpValue}.
     */
    private String resolveLspValue(PluginContext ctx, String value, List<String> missingVars) {
        if (value == null) {
            return null;
        }
        String resolved = PluginVariables.substitutePluginPaths(value, ctx.root(), ctx.dataDir());
        resolved = PluginVariables.substituteUserConfig(resolved, ctx.userConfig());
        return PluginVariables.expandEnvVars(resolved, missingVars);
    }

    private void collectMcpServersFromSpec(PluginContext ctx, String rel,
                                           Map<String, JsonNode> raw, List<PluginError> errors) {
        if (Strings.CS.endsWith(rel, ".mcpb") || Strings.CS.endsWith(rel, ".dxt")) {
            try {
                McpbBundleLoader.Result result = mcpbLoader.load(rel, ctx.root(),
                    serverName -> loadMcpServerUserConfig(ctx.pluginId(), serverName));
                if (!result.needsConfig()) {
                    raw.put(result.serverName(), result.mcpConfig());
                } else {
                    LOG.debug("MCPB {} for plugin {} needs configuration: {}", rel,
                        ctx.pluginName(), String.join("; ", result.validationErrors()));
                }
            } catch (Exception e) {
                String message = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
                if ((Strings.CS.startsWith(rel, "http://") || Strings.CS.startsWith(rel, "https://"))
                        && (Strings.CI.contains(message, "download")
                            || Strings.CI.contains(message, "network")
                            || Strings.CS.contains(message, "HTTP"))) {
                    errors.add(new PluginError.McpbDownloadFailed(ctx.pluginId(),
                        ctx.pluginName(), rel, message));
                } else if (Strings.CI.contains(message, "manifest")
                        || Strings.CI.contains(message, "user config")) {
                    errors.add(new PluginError.McpbInvalidManifest(ctx.pluginId(),
                        ctx.pluginName(), rel, message));
                } else {
                    errors.add(new PluginError.McpbExtractFailed(ctx.pluginId(),
                        ctx.pluginName(), rel, message));
                }
            }
            return;
        }
        collectMcpServersFromFile(ctx, ctx.root().resolve(rel), raw, errors);
    }

    /** Merges per-MCPB settings values with keychain-backed server secrets. */
    private Map<String, Object> loadMcpServerUserConfig(String pluginId, String serverName) {
        Map<String, Object> values = new LinkedHashMap<>();
        JsonNode config = settings.pluginConfig(pluginId);
        JsonNode stored = config == null ? null : config.path("mcpServers").get(serverName);
        if (stored != null && stored.isObject()) {
            stored.fields().forEachRemaining(field -> values.put(field.getKey(),
                optionValue(field.getValue())));
        }
        if (secureStorage != null) {
            secureStorage.read().map(SecureStorageData::pluginSecrets)
                .map(secrets -> secrets.get(pluginId + "/" + serverName))
                .ifPresent(values::putAll);
        }
        return values;
    }

    private static Object optionValue(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        if (value.isIntegralNumber()) return value.asLong();
        if (value.isFloatingPointNumber()) return value.asDouble();
        if (value.isArray()) {
            List<String> result = new ArrayList<>();
            value.forEach(item -> result.add(item.asText()));
            return result;
        }
        return value.asText();
    }


    private void collectMcpServersFromFile(PluginContext ctx, Path file,
                                           Map<String, JsonNode> raw,
                                           List<PluginError> errors) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JsonUtils.readJson(file);
            JsonNode servers = root.has("mcpServers") && root.get("mcpServers").isObject()
                ? root.get("mcpServers") : root;
            if (servers.isObject()) {
                servers.fields().forEachRemaining(f -> raw.put(f.getKey(), f.getValue()));
            }
        } catch (Exception e) {
            errors.add(new PluginError.McpConfigInvalid("plugin:" + ctx.pluginName(),
                ctx.pluginName(), file.toString(), e.getMessage()));
        }
    }

    private McpServerConfig buildMcpConfig(PluginContext ctx, String serverName,
                                           JsonNode node, List<PluginError> errors) {
        if (!node.isObject()) {
            errors.add(new PluginError.McpConfigInvalid("plugin:" + ctx.pluginName(),
                ctx.pluginName(), serverName, "server config is not an object"));
            return null;
        }
        List<String> missingVars = new ArrayList<>();
        String type = node.path("type").asText("stdio");
        String scopedName = "plugin:" + ctx.pluginName() + ":" + serverName;
        Map<String, String> userConfig = ctx.mcpUserConfig(serverName);

        McpServerConfig config;
        if (StringUtils.isBlank(type) || Strings.CS.equals("stdio", type)) {
            String command = resolveMcpValue(ctx, userConfig,
                node.path("command").asText(null), missingVars);
            List<String> args = new ArrayList<>();
            if (node.get("args") != null && node.get("args").isArray()) {
                for (JsonNode arg : node.get("args")) {
                    args.add(resolveMcpValue(ctx, userConfig, arg.asText(), missingVars));
                }
            }
            // CLAUDE_PLUGIN_ROOT / CLAUDE_PLUGIN_DATA are injected first, declared

            // resolvePluginMcpEnvironment.
            Map<String, String> env = new LinkedHashMap<>();
            env.put("CLAUDE_PLUGIN_ROOT", ctx.root().toString());
            env.put("CLAUDE_PLUGIN_DATA", ctx.dataDir().toString());
            if (node.get("env") != null && node.get("env").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.get("env").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (Strings.CS.equals("CLAUDE_PLUGIN_ROOT", field.getKey())
                        || Strings.CS.equals("CLAUDE_PLUGIN_DATA", field.getKey())) {
                        continue;
                    }
                    env.put(field.getKey(),
                        resolveMcpValue(ctx, userConfig, field.getValue().asText(), missingVars));
                }
            }
            config = new McpServerConfig(scopedName, command, args, env, false,
                "stdio", null, null);
        } else {
            String url = resolveMcpValue(ctx, userConfig,
                node.path("url").asText(null), missingVars);
            Map<String, String> headers = new LinkedHashMap<>();
            if (node.get("headers") != null && node.get("headers").isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.get("headers").fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    headers.put(field.getKey(),
                    resolveMcpValue(ctx, userConfig, field.getValue().asText(), missingVars));
                }
            }
            config = new McpServerConfig(scopedName, null, List.of(), Map.of(), false,
                type, url, headers);
        }

        if (!missingVars.isEmpty()) {
            errors.add(new PluginError.McpConfigInvalid("plugin:" + ctx.pluginName(),
                ctx.pluginName(), serverName,
                "Missing environment variables: "
                    + String.join(", ", new LinkedHashSet<>(missingVars))));
        }
        return config;
    }


    private String resolveMcpValue(PluginContext ctx, Map<String, String> userConfig,
                                   String value, List<String> missingVars) {
        if (value == null) {
            return null;
        }
        String resolved = PluginVariables.substitutePluginPaths(value, ctx.root(), ctx.dataDir());
        resolved = PluginVariables.substituteUserConfig(resolved, userConfig);
        return PluginVariables.expandEnvVars(resolved, missingVars);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────


    private static String fmString(Map<String, Object> fm, String key) {
        String raw = FrontmatterParser.getString(fm, key);
        return unquoteFrontmatterScalar(raw);
    }

    private static String unquoteFrontmatterScalar(String raw) {
        if (raw == null || raw.length() < 2) {
            return raw;
        }
        char first = raw.charAt(0);
        char last = raw.charAt(raw.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }

    private static List<JsonNode> streamToList(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private static Path normalize(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException _) {
            return p.toAbsolutePath().normalize();
        }
    }
}
