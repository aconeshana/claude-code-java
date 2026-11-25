package com.claudecode.cli;

import com.claudecode.api.ApiConfig;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.model.ModelSkillVisibility;
import com.claudecode.mcp.CapabilityConfigStore;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpServerScope;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.runtime.capabilities.SessionCapabilityPort;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition-root controller implementing both {@link SessionCapabilityFilter} (hot-path
 * engine/tool/skill filtering) and {@link SessionCapabilityPort} (TUI + WebUI inspection and
 * session/default switching).
 */
@Explanation("Manages per-session progressive capability overrides backed by ~/.claude.json project defaults")
final class CliSessionCapabilityService implements SessionCapabilityPort {

    private static final Logger LOG = LoggerFactory.getLogger(CliSessionCapabilityService.class);
    private static final String DEFAULT_SESSION_KEY = "__default__";

    private static final class SessionState {
        final Set<String> baseDisabledTools = ConcurrentHashMap.newKeySet();
        final Set<String> baseDisabledSkills = ConcurrentHashMap.newKeySet();
        final Set<String> baseDisabledMcpServers = ConcurrentHashMap.newKeySet();
        final Set<String> baseDisabledMcpTools = ConcurrentHashMap.newKeySet();

        final Map<String, Boolean> toolOverrides = new ConcurrentHashMap<>();
        final Map<String, Boolean> skillOverrides = new ConcurrentHashMap<>();
        final Map<String, Boolean> mcpServerOverrides = new ConcurrentHashMap<>();
        final Map<String, Boolean> mcpToolOverrides = new ConcurrentHashMap<>();

        volatile Set<String> frozenEagerTools;

        SessionState(CapabilityConfigStore.ProjectDefaults defaults) {
            baseDisabledTools.addAll(defaults.disabledTools());
            baseDisabledSkills.addAll(defaults.disabledSkills());
            baseDisabledMcpServers.addAll(defaults.disabledMcpServers());
            baseDisabledMcpTools.addAll(defaults.disabledMcpTools());
        }
    }

    private final ToolRegistry toolRegistry;
    private final SkillLoader skillLoader;
    private final McpRuntime mcpRuntime;
    private final CustomModelCatalog customModels;
    private final ApiConfig.ApiProvider fallbackApiProvider;
    private final Supplier<String> defaultSessionIdSupplier;
    private final Supplier<Path> defaultProjectDirSupplier;
    private final Supplier<String> defaultModelSupplier;
    private volatile CommandRegistry commandRegistry;
    private volatile Runnable onCapabilityChanged;

    private final Map<String, Path> sessionProjects = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    CliSessionCapabilityService(
            ToolRegistry toolRegistry,
            SkillLoader skillLoader,
            McpRuntime mcpRuntime,
            CustomModelCatalog customModels,
            ApiConfig.ApiProvider fallbackApiProvider,
            Supplier<String> defaultSessionIdSupplier,
            Supplier<Path> defaultProjectDirSupplier,
            Supplier<String> defaultModelSupplier) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.skillLoader = Objects.requireNonNull(skillLoader, "skillLoader");
        this.mcpRuntime = mcpRuntime;
        this.customModels = customModels;
        this.fallbackApiProvider = fallbackApiProvider != null
            ? fallbackApiProvider : ApiConfig.ApiProvider.ANTHROPIC;
        this.defaultSessionIdSupplier = defaultSessionIdSupplier != null
            ? defaultSessionIdSupplier : () -> "";
        this.defaultProjectDirSupplier = defaultProjectDirSupplier != null
            ? defaultProjectDirSupplier : () -> Path.of(System.getProperty("user.dir", "."));
        this.defaultModelSupplier = defaultModelSupplier != null
            ? defaultModelSupplier : ModelNames::defaultMainLoopModel;
    }

    void attachCommandRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    void setOnCapabilityChanged(Runnable listener) {
        this.onCapabilityChanged = listener;
    }

    @Override
    public void bindSessionProject(String sessionId, String projectPath) {
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(projectPath)) return;
        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        sessionProjects.put(sessionId, path);
        Path projectSkillsDir = path.resolve(".claude").resolve("skills");
        if (Files.isDirectory(projectSkillsDir)) {
            skillLoader.addSource(Skill.SkillSource.PROJECT, projectSkillsDir);
        }
        sessions.computeIfAbsent(sessionId,
            _ -> new SessionState(CapabilityConfigStore.loadProjectDefaults(path)));
        ensureSessionMcpConnected(sessionId);
    }

    @Override
    public void resetSession(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        sessions.remove(key);
        toolRegistry.clearFrozenSession(sessionId);
    }

    /**
     * Connects any MCP server that is enabled in the given session (e.g. if another session
     * only connected a subset or if defaults changed before this session started).
     */
    void ensureSessionMcpConnected(String sessionId) {
        if (mcpRuntime == null) return;
        Path projectDir = resolveProjectDir(sessionId);
        McpConfig config = McpConfigLoader.loadConfig(projectDir);
        for (String serverName : config.servers().keySet()) {
            if (isMcpServerEnabled(sessionId, serverName)) {
                try {
                    mcpRuntime.ensureServerConnected(serverName);
                } catch (RuntimeException e) {
                    LOG.debug("Deferred MCP connect failed for '{}': {}", serverName, e.getMessage());
                }
            }
        }
    }

    /** Filters connected MCP server instructions to servers enabled in {@code sessionId}. */
    Map<String, String> sessionMcpInstructions(String sessionId) {
        if (mcpRuntime == null || mcpRuntime.clientRuntime() == null) return Map.of();
        Map<String, String> all = mcpRuntime.clientRuntime().getServerInstructions();
        if (all == null || all.isEmpty()) return Map.of();
        Map<String, String> filtered = new LinkedHashMap<>();
        all.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .filter(e -> isMcpServerEnabled(sessionId, e.getKey()))
            .forEach(e -> filtered.put(e.getKey(), e.getValue()));
        return Collections.unmodifiableMapCompat(filtered);
    }

    private static final class Collections {
        static <K, V> Map<K, V> unmodifiableMapCompat(Map<K, V> map) {
            return java.util.Collections.unmodifiableMap(map);
        }
    }

    @Override
    public boolean isBuiltInToolEnabled(String sessionId, String toolName) {
        if (StringUtils.isBlank(toolName)) return false;
        if (KERNEL_TOOLS.contains(toolName)) return true;
        SessionState state = stateFor(sessionId);
        Boolean override = state.toolOverrides.get(toolName);
        if (override != null) return override;
        return !state.baseDisabledTools.contains(toolName);
    }

    @Override
    public boolean isMcpServerEnabled(String sessionId, String serverName) {
        if (StringUtils.isBlank(serverName)) return true;
        SessionState state = stateFor(sessionId);
        Boolean override = state.mcpServerOverrides.get(serverName);
        if (override != null) return override;
        return !state.baseDisabledMcpServers.contains(serverName);
    }

    @Override
    public boolean isMcpToolEnabled(String sessionId, String fullToolName) {
        if (StringUtils.isBlank(fullToolName)) return false;
        String server = parseMcpServer(fullToolName);
        if (server != null && !isMcpServerEnabled(sessionId, server)) {
            return false;
        }
        SessionState state = stateFor(sessionId);
        Boolean override = state.mcpToolOverrides.get(fullToolName);
        if (override != null) return override;
        return !state.baseDisabledMcpTools.contains(fullToolName);
    }

    @Override
    public boolean isSkillEnabled(String sessionId, String skillName) {
        if (StringUtils.isBlank(skillName)) return false;
        SessionState state = stateFor(sessionId);
        Boolean override = state.skillOverrides.get(skillName);
        if (override != null) return override;
        return !state.baseDisabledSkills.contains(skillName);
    }

    @Override
    public Set<String> frozenInitialEagerTools(String sessionId, Supplier<Set<String>> computer) {
        SessionState state = stateFor(sessionId);
        Set<String> existing = state.frozenEagerTools;
        if (existing != null) return existing;
        synchronized (state) {
            existing = state.frozenEagerTools;
            if (existing == null) {
                existing = Set.copyOf(computer.get());
                state.frozenEagerTools = existing;
            }
            return existing;
        }
    }

    public ModelApiProtocol resolveProtocol(String modelOverride) {
        String effectiveModel = resolveModel(modelOverride);
        String parsed = ModelNames.parseUserSpecifiedModel(effectiveModel);
        ModelApiProtocol fallback = fallbackApiProvider == ApiConfig.ApiProvider.OPENAI_COMPAT
            ? ModelApiProtocol.OPENAI_CHAT
            : ModelApiProtocol.ANTHROPIC;
        if (customModels == null) return fallback;
        return customModels.find(parsed)
            .or(() -> customModels.find(effectiveModel))
            .map(CustomModelConfig::protocol)
            .orElse(fallback);
    }

    @Override
    public CapabilitiesSnapshot snapshot(String sessionId, String modelOverride) {
        String resolvedSessionId = effectiveSessionId(sessionId);
        Path projectDir = resolveProjectDir(resolvedSessionId);
        CapabilityConfigStore.ProjectDefaults diskDefaults =
            CapabilityConfigStore.loadProjectDefaults(projectDir);
        String model = resolveModel(modelOverride);
        String protocol = resolveProtocol(model).configValue();
        Set<String> frozenEager = toolRegistry.getFrozenInitialEagerToolNames(resolvedSessionId);

        int savedTokens = 0;

        // 1. Built-in tools
        List<BuiltInToolItem> builtInItems = new ArrayList<>();
        List<Tool<?, ?>> allRegistered = toolRegistry.getAll().stream()
            .filter(Tool::isEnabled)
            .filter(t -> !t.isMcp() && !Strings.CS.startsWith(t.name(), "mcp__"))
            .sorted(Comparator.comparing(Tool::name))
            .toList();
        for (Tool<?, ?> tool : allRegistered) {
            String name = tool.name();
            boolean kernel = KERNEL_TOOLS.contains(name);
            boolean sessionEnabled = isBuiltInToolEnabled(resolvedSessionId, name);
            boolean defaultEnabled = kernel || !diskDefaults.disabledTools().contains(name);
            JsonNode schema = tool.inputSchema();
            String desc = StringUtils.defaultString(tool.description());
            int tokens = estimateToolTokens(name, desc, schema);
            String deliveryMode;
            if (!sessionEnabled) {
                deliveryMode = DeliveryMode.DISABLED.wire();
                if (!frozenEager.contains(name)) {
                    savedTokens += tokens;
                }
            } else if (frozenEager.contains(name)) {
                deliveryMode = DeliveryMode.PREFIX_EAGER.wire();
            } else {
                deliveryMode = DeliveryMode.PROGRESSIVE_DEFERRED.wire();
                savedTokens += tokens;
            }
            builtInItems.add(new BuiltInToolItem(
                name, desc, schema, tokens, kernel, sessionEnabled, defaultEnabled, deliveryMode));
        }

        // 2. Skills
        List<SkillItem> skillItems = new ArrayList<>();
        List<Skill> allSkills = skillLoader.loadAll().stream()
            .sorted(Comparator.comparing(Skill::name))
            .toList();
        for (Skill skill : allSkills) {
            String name = skill.name();
            boolean sessionEnabled = isSkillEnabled(resolvedSessionId, name);
            boolean defaultEnabled = !diskDefaults.disabledSkills().contains(name);
            boolean bundled = skill.source() == Skill.SkillSource.BUNDLED
                || skill.source() == Skill.SkillSource.BUILTIN;
            boolean protocolVisible = !skill.disableModelInvocation()
                && ModelSkillVisibility.isVisible(name, bundled, model);
            String desc = StringUtils.defaultString(skill.description());
            int tokens = estimateTextTokens("- " + name + ": " + desc);
            if (!sessionEnabled) {
                savedTokens += tokens;
            }
            skillItems.add(new SkillItem(
                name,
                skill.source().name().toLowerCase(Locale.ROOT),
                desc,
                tokens,
                sessionEnabled,
                defaultEnabled,
                protocolVisible));
        }

        // 3. MCP servers & tools
        List<McpServerItem> mcpServerItems = buildMcpServerItems(
            resolvedSessionId, projectDir, diskDefaults);
        for (McpServerItem srv : mcpServerItems) {
            savedTokens += srv.tokens();
        }

        return new CapabilitiesSnapshot(
            resolvedSessionId,
            model,
            protocol,
            savedTokens,
            builtInItems,
            skillItems,
            mcpServerItems);
    }

    private List<McpServerItem> buildMcpServerItems(
            String sessionId, Path projectDir, CapabilityConfigStore.ProjectDefaults diskDefaults) {
        McpConfig config = McpConfigLoader.loadConfig(projectDir);
        Map<String, String> liveStatuses = mcpRuntime != null
            ? mcpRuntime.snapshotServerStatuses() : Map.of();
        McpClientRuntime liveClient = mcpRuntime != null ? mcpRuntime.clientRuntime() : null;
        Map<String, String> instructionsMap = liveClient != null
            ? liveClient.getServerInstructions() : Map.of();

        // Group any registered MCPTools by server in case a dynamic/SDK server is active
        Map<String, List<MCPTool>> registeredMcpToolsByServer = new LinkedHashMap<>();
        for (Tool<?, ?> t : toolRegistry.getAll()) {
            if (t instanceof MCPTool mcpTool && mcpTool.getToolInfo() != null) {
                registeredMcpToolsByServer
                    .computeIfAbsent(mcpTool.getToolInfo().serverId(), _ -> new ArrayList<>())
                    .add(mcpTool);
            }
        }

        LinkedHashSet<String> allServerNames = new LinkedHashSet<>(config.servers().keySet());
        allServerNames.addAll(liveStatuses.keySet());
        allServerNames.addAll(registeredMcpToolsByServer.keySet());

        List<McpServerItem> items = new ArrayList<>();
        for (String serverName : allServerNames) {
            McpServerConfig serverCfg = config.servers().get(serverName);
            McpServerScope scope = serverCfg != null
                ? config.scopeOf(serverName) : McpServerScope.DYNAMIC;
            boolean manageable = scope != McpServerScope.DYNAMIC;
            String transport = serverCfg != null ? serverCfg.transportType() : "dynamic";
            boolean sessionEnabled = isMcpServerEnabled(sessionId, serverName);
            boolean defaultEnabled = !diskDefaults.disabledMcpServers().contains(serverName)
                && (serverCfg == null || !serverCfg.disabled());

            String rawStatus = liveStatuses.getOrDefault(serverName,
                liveClient != null && liveClient.getConnectedServerIds().contains(serverName)
                    ? "connected" : (defaultEnabled ? "disconnected" : "disabled"));
            String effectiveStatus = !sessionEnabled ? "disabled" : rawStatus;
            String instructions = StringUtils.defaultString(instructionsMap.get(serverName));

            List<McpToolItem> toolItems = new ArrayList<>();
            Map<String, McpToolInfo> discovered = new LinkedHashMap<>();
            if (liveClient != null && liveClient.getConnectedServerIds().contains(serverName)) {
                for (McpToolInfo info : liveClient.listToolsForServer(serverName)) {
                    discovered.put(info.name(), info);
                }
            }
            for (MCPTool reg : registeredMcpToolsByServer.getOrDefault(serverName, List.of())) {
                McpToolInfo info = reg.getToolInfo();
                if (info != null) discovered.putIfAbsent(info.name(), info);
            }

            int serverTokens = StringUtils.isNotBlank(instructions)
                ? estimateTextTokens(instructions) : 0;
            for (McpToolInfo info : discovered.values().stream()
                    .sorted(Comparator.comparing(McpToolInfo::name)).toList()) {
                String fullName = "mcp__" + serverName + "__" + info.name();
                boolean toolSessionEnabled = sessionEnabled && isMcpToolEnabled(sessionId, fullName);
                boolean toolDefaultEnabled = defaultEnabled
                    && !diskDefaults.disabledMcpTools().contains(fullName);
                String desc = StringUtils.defaultString(info.description());
                int tTokens = estimateToolTokens(fullName, desc, info.inputSchema());
                serverTokens += tTokens;
                toolItems.add(new McpToolItem(
                    serverName,
                    info.name(),
                    fullName,
                    desc,
                    info.inputSchema(),
                    tTokens,
                    toolSessionEnabled,
                    toolDefaultEnabled));
            }

            items.add(new McpServerItem(
                serverName,
                scope.name().toLowerCase(Locale.ROOT),
                transport,
                effectiveStatus,
                instructions,
                serverTokens,
                manageable,
                sessionEnabled,
                defaultEnabled,
                toolItems));
        }
        items.sort(Comparator.comparing(McpServerItem::name));
        return items;
    }

    @Override
    public ToggleOutcome toggle(ToggleRequest request) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(request.kind(), "kind is required");
        if (StringUtils.isBlank(request.name())) {
            throw new IllegalArgumentException("Capability name is required");
        }
        String resolvedSessionId = effectiveSessionId(request.sessionId());
        Path projectDir = resolveProjectDir(resolvedSessionId);
        Scope scope = request.scope() != null ? request.scope() : Scope.SESSION;
        String name = request.name().strip();
        boolean enabled = request.enabled();

        if (request.kind() == Kind.TOOL && KERNEL_TOOLS.contains(name)) {
            throw new IllegalArgumentException(
                "Kernel tool '" + name + "' is required for progressive discovery and cannot be disabled.");
        }

        SessionState state = stateFor(resolvedSessionId);
        boolean changed;
        String scopeLabel = scope == Scope.DEFAULT ? "default + current session" : "current session";

        try {
            changed = switch (request.kind()) {
                case TOOL -> applyToolToggle(state, projectDir, name, enabled, scope);
                case SKILL -> applySkillToggle(state, projectDir, name, enabled, scope);
                case MCP_SERVER -> applyMcpServerToggle(
                    resolvedSessionId, state, projectDir, name, enabled, scope);
                case MCP_TOOL -> {
                    String fullName = normalizeMcpToolName(request.serverName(), name);
                    yield applyMcpToolToggle(state, projectDir, fullName, enabled, scope);
                }
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist capability switch: " + e.getMessage(), e);
        }

        notifyChanged();
        String actionWord = enabled ? "Enabled" : "Disabled";
        String message = changed
            ? actionWord + " " + request.kind().wire() + " '" + name + "' (" + scopeLabel + ")."
            : request.kind().wire() + " '" + name + "' is already "
                + (enabled ? "enabled" : "disabled") + " (" + scopeLabel + ").";
        return new ToggleOutcome(changed, message, snapshot(resolvedSessionId, null));
    }

    private boolean applyToolToggle(
            SessionState state, Path projectDir, String toolName, boolean enabled, Scope scope)
            throws IOException {
        boolean before = evalOverride(state.toolOverrides, state.baseDisabledTools, toolName);
        if (scope == Scope.DEFAULT) {
            boolean diskChanged = CapabilityConfigStore.setToolDisabled(projectDir, toolName, !enabled);
            updateBaseSet(state.baseDisabledTools, toolName, !enabled);
            state.toolOverrides.remove(toolName);
            return diskChanged || before != enabled;
        }
        state.toolOverrides.put(toolName, enabled);
        return before != enabled;
    }

    private boolean applySkillToggle(
            SessionState state, Path projectDir, String skillName, boolean enabled, Scope scope)
            throws IOException {
        boolean before = evalOverride(state.skillOverrides, state.baseDisabledSkills, skillName);
        if (scope == Scope.DEFAULT) {
            boolean diskChanged = CapabilityConfigStore.setSkillDisabled(projectDir, skillName, !enabled);
            updateBaseSet(state.baseDisabledSkills, skillName, !enabled);
            state.skillOverrides.remove(skillName);
            return diskChanged || before != enabled;
        }
        state.skillOverrides.put(skillName, enabled);
        return before != enabled;
    }

    private boolean applyMcpServerToggle(
            String sessionId, SessionState state, Path projectDir,
            String serverName, boolean enabled, Scope scope) throws IOException {
        boolean before = evalOverride(
            state.mcpServerOverrides, state.baseDisabledMcpServers, serverName);
        boolean diskChanged = false;
        if (scope == Scope.DEFAULT) {
            diskChanged = CapabilityConfigStore.setMcpServerDisabled(projectDir, serverName, !enabled);
            updateBaseSet(state.baseDisabledMcpServers, serverName, !enabled);
            state.mcpServerOverrides.remove(serverName);
        } else {
            state.mcpServerOverrides.put(serverName, enabled);
        }

        if (enabled && mcpRuntime != null) {
            try {
                mcpRuntime.ensureServerConnected(serverName);
                syncServerPromptsIfNeeded();
            } catch (RuntimeException e) {
                LOG.warn("MCP server '{}' enabled for {} but live connect reported: {}",
                    serverName, sessionId, e.getMessage());
            }
        } else if (!enabled && scope == Scope.DEFAULT && mcpRuntime != null
                && !anySessionExplicitlyEnablesServer(serverName)) {
            try {
                mcpRuntime.toggleServer(serverName, false);
            } catch (RuntimeException e) {
                LOG.debug("MCP server '{}' disconnect skipped: {}", serverName, e.getMessage());
            }
        }
        return diskChanged || before != enabled;
    }

    private boolean applyMcpToolToggle(
            SessionState state, Path projectDir, String fullName, boolean enabled, Scope scope)
            throws IOException {
        boolean before = evalOverride(
            state.mcpToolOverrides, state.baseDisabledMcpTools, fullName);
        if (scope == Scope.DEFAULT) {
            boolean diskChanged = CapabilityConfigStore.setMcpToolDisabled(
                projectDir, fullName, !enabled);
            updateBaseSet(state.baseDisabledMcpTools, fullName, !enabled);
            state.mcpToolOverrides.remove(fullName);
            return diskChanged || before != enabled;
        }
        state.mcpToolOverrides.put(fullName, enabled);
        return before != enabled;
    }

    private boolean anySessionExplicitlyEnablesServer(String serverName) {
        return sessions.values().stream()
            .anyMatch(s -> Boolean.TRUE.equals(s.mcpServerOverrides.get(serverName)));
    }

    private void syncServerPromptsIfNeeded() {
        CommandRegistry registry = this.commandRegistry;
        if (registry == null || mcpRuntime == null) return;
        McpClientRuntime live = mcpRuntime.clientRuntime();
        if (live == null) return;
        CliMcpPromptAdapter adapter = new CliMcpPromptAdapter(live);
        mcpRuntime.syncPromptsToRegistry(info ->
            registry.register(new McpPromptCommand(CliMcpPromptAdapter.definition(info), adapter)));
    }

    private void notifyChanged() {
        Runnable listener = this.onCapabilityChanged;
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException _) {
                // Best-effort UI/cache invalidation.
            }
        }
    }

    private SessionState stateFor(String sessionId) {
        String key = normalizeSessionKey(sessionId);
        return sessions.computeIfAbsent(key, _ -> {
            Path projectDir = resolveProjectDir(sessionId);
            return new SessionState(CapabilityConfigStore.loadProjectDefaults(projectDir));
        });
    }

    private String effectiveSessionId(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) return sessionId.strip();
        String fallback = defaultSessionIdSupplier.get();
        return StringUtils.isNotBlank(fallback) ? fallback : "";
    }

    private String normalizeSessionKey(String sessionId) {
        String effective = effectiveSessionId(sessionId);
        return StringUtils.isBlank(effective) ? DEFAULT_SESSION_KEY : effective;
    }

    private Path resolveProjectDir(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) {
            Path bound = sessionProjects.get(sessionId);
            if (bound != null) return bound;
        }
        Path def = defaultProjectDirSupplier.get();
        return def != null ? def.toAbsolutePath().normalize()
            : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private String resolveModel(String modelOverride) {
        if (StringUtils.isNotBlank(modelOverride)) return modelOverride.strip();
        String supplied = defaultModelSupplier.get();
        return StringUtils.isNotBlank(supplied) ? supplied : ModelNames.defaultMainLoopModel();
    }

    private static boolean evalOverride(
            Map<String, Boolean> overrides, Set<String> baseDisabled, String name) {
        Boolean ov = overrides.get(name);
        return ov != null ? ov : !baseDisabled.contains(name);
    }

    private static void updateBaseSet(Set<String> set, String name, boolean disabled) {
        if (disabled) set.add(name);
        else set.remove(name);
    }

    private static String normalizeMcpToolName(String serverName, String toolName) {
        if (Strings.CS.startsWith(toolName, "mcp__")) {
            return toolName;
        }
        if (StringUtils.isNotBlank(serverName)) {
            return "mcp__" + serverName.strip() + "__" + toolName;
        }
        return toolName;
    }

    private static String parseMcpServer(String fullToolName) {
        if (fullToolName != null && Strings.CS.startsWith(fullToolName, "mcp__")) {
            String rest = fullToolName.substring("mcp__".length());
            int idx = rest.indexOf("__");
            if (idx > 0) return rest.substring(0, idx);
        }
        return null;
    }

    private static int estimateToolTokens(String name, String description, JsonNode schema) {
        String schemaStr = schema != null ? schema.toString() : "{}";
        return estimateTextTokens(name + "\n" + description + "\n" + schemaStr);
    }

    private static int estimateTextTokens(String text) {
        if (StringUtils.isEmpty(text)) return 0;
        return Math.max(1, (int) Math.round(text.length() / 4.0));
    }
}
