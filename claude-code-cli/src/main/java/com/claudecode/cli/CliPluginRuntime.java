package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.plugins.PluginCommandSync;
import com.claudecode.commands.workflows.WorkflowCommandSync;
import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.mcp.McpClientRuntime;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Live injection hub for the plugin subsystem: owns the current {@link PluginRuntimeSnapshot} and
 * pushes each component into its runtime channel — commands into the {@link CommandRegistry},
 * agents into {@link AgentDefinitionLoader}, skills and model-invocable plugin commands into {@link
 * SkillLoader}, hooks into {@link HookEngine}, MCP servers into {@link
 * com.claudecode.mcp.McpClientManager} (+ their tools into {@link ToolRegistry}).
 */
final class CliPluginRuntime implements CliPluginRuntimeView, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CliPluginRuntime.class);

    /**
     * Installs a hook run at the end of every {@link #refresh}. The CLI uses
     * this to reload LSP servers from the new plugin snapshot without forcing a
     * {@code claude-code-commands → claude-code-lsp} dependency. A null callback
     * (the default) is simply skipped.
     */
    public void setPostRefreshCallback(Consumer<PluginRuntimeSnapshot> callback) {
        this.postRefreshCallback = callback;
    }


    private final PluginRuntimeLoader loader;
    private final String cwd;
    private final SkillLoader skillLoader;
    private final HookEngine hookEngine;
    private final McpClientRuntime mcpClientManager;
    private final ToolRegistry toolRegistry;
    private final PluginCommandSync commandSync = new PluginCommandSync();
    private final WorkflowCommandSync workflowCommandSync = new WorkflowCommandSync();
    private CliSkillCommandSync skillCommandSync = new CliSkillCommandSync();

    private CommandRegistry commandRegistry;
    private volatile PluginRuntimeSnapshot current = PluginRuntimeSnapshot.empty();
/** Optional hook fired at the end of {@link #refresh} — the CLI installs
     *  one that reloads LSP servers from the freshly reloaded plugin snapshot.
     *  Kept as a {@code Runnable} (not an LSP-typed callback) so this commands
     *  module never depends on {@code claude-code-lsp}. */
    private volatile Consumer<PluginRuntimeSnapshot> postRefreshCallback;
    private volatile boolean closed;
    /**
     * Plugin MCP configurations claimed by this runtime. A name alone is not a
     * sufficient identity: changing command/args/env/URL/headers must evict the
     * old connection and its tool schema before a fresh handshake.
     */
    private final Map<String, McpServerConfig> activePluginServers = new ConcurrentHashMap<>();
    /** Per-server locks prevent a stale async handshake from overwriting a
     * newer generation after {@code /reload-plugins}. */
    private final Map<String, Object> pluginMcpLocks = new ConcurrentHashMap<>();

    CliPluginRuntime(PluginRuntimeLoader loader, String cwd,
                         SkillLoader skillLoader, HookEngine hookEngine,
                         McpClientRuntime mcpClientManager, ToolRegistry toolRegistry) {
        this.loader = loader;
        this.cwd = cwd;
        this.skillLoader = skillLoader;
        this.hookEngine = hookEngine;
        this.mcpClientManager = mcpClientManager;
        this.toolRegistry = toolRegistry;
    }

    /** The snapshot currently injected into the channels. */
    @Override
    public PluginRuntimeSnapshot currentSnapshot() {
        return current;
    }

    @Override
    public Summary summary() {
        PluginRuntimeSnapshot snapshot = current;
        return new Summary(snapshot.commands().size(), snapshot.agents().size(),
            snapshot.skillDirs().size(), snapshot.mcpServers().size(), snapshot.errors().size());
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return current.errors().stream()
            .map(error -> new Diagnostic(error.source(), error.plugin(), error.getMessage()))
            .toList();
    }

    /**
     * Startup path: load from disk and inject every channel. Commands are only
     * synced once a registry has been attached (see
     * {@link #attachCommandRegistry}).
     */
    public synchronized PluginRuntimeSnapshot loadAndInject() {
        ensureOpen();
        current = loader.loadAll();
        injectAll();
        return current;
    }

    /**
     * Attaches the interactive command registry (created after startup plugin
     * load in the CLI wiring) and syncs the current command generation into it.
     */
    public synchronized void attachCommandRegistry(CommandRegistry registry) {
        attachCommandRegistry(registry, null);
    }

    @Override
    public synchronized void attachCommandRegistry(
            CommandRegistry registry, CliSkillCommandSync sharedSkillCommandSync) {
        ensureOpen();
        this.commandRegistry = registry;
        if (sharedSkillCommandSync != null) this.skillCommandSync = sharedSkillCommandSync;
        if (registry != null) {
            commandSync.sync(registry, current.commands());
        }
    }

    /**
     * {@code /reload-plugins}: re-read everything from disk, swap all
     * channels, return the load statistics.
     */
    @Override
    public synchronized RefreshResult refresh() {
        ensureOpen();
        current = loader.loadAll();
        injectAll();
        // LSP servers are reloaded out-of-band (see setPostRefreshCallback) so the
        // CLI can rebuild them from this freshly reloaded snapshot. The callback

        
        if (postRefreshCallback != null) {
            try {
                postRefreshCallback.accept(current);
            } catch (RuntimeException e) {
                LOG.warn("Post-refresh LSP reload failed: {}", e.getMessage());
            }
        }
        int agentCount = countAgents();
        return new RefreshResult(
            current.enabledCount(),
            current.disabledCount(),
            current.commands().size(),
            agentCount,
            current.hookCommandCount(),
            current.mcpServers().size(),
            current.lspServers().size(),
            current.errors().size());
    }

    /** Reloads skill discovery without rebuilding unrelated plugin channels. */
    @Override
    public synchronized int reloadSkills() {
        ensureOpen();
        skillLoader.invalidateCache();
        List<Skill> skills = skillLoader.loadAll();
        int count = skills.size();
        if (commandRegistry != null) {
            skillCommandSync.sync(commandRegistry, skills, Path.of(cwd));
        }
        return count;
    }

    @Override
    public synchronized void syncWorkflowCommands(Path cwd) {
        if (commandRegistry == null) return;
        CliHeadlessSessionRunner.syncWorkflowCommands(
            commandRegistry, cwd, this, workflowCommandSync);
    }

    // ── channel injection ────────────────────────────────────────────────────

    private void injectAll() {
        PluginRuntimeSnapshot snapshot = current;
        // Provider reads the volatile snapshot; setting it also clears the
// per-cwd agent cache so the next getAll rebuild picks up changes.
        AgentDefinitionLoader.setPluginAgentsProvider(() -> current.agents());
        Path path = Path.of(cwd);
        if (skillLoader != null) {
            skillLoader.setPluginCommandSkills(snapshot.commands().stream()
                .map(CliPluginRuntime::asModelSkill)
                .toList());
            skillLoader.setPluginSkillRoots(snapshot.skillDirs().stream()
                .map(d -> new SkillLoader.PluginSkillRoot(
                    d.pluginName(), d.directory(), d.directSkillName()))
                .toList());
            if (commandRegistry != null) {
                try {
                    skillLoader.invalidateCache();
                    skillCommandSync.sync(
                        commandRegistry, List.copyOf(skillLoader.loadAll()), path);
                } catch (RuntimeException failure) {
                    LOG.warn("Plugin refresh kept the previous Skill command generation: {}",
                        failure.getMessage());
                }
            }
        }
        if (commandRegistry != null) {
            // Plugin markdown commands win collisions with their Skill view.
            commandSync.sync(commandRegistry, snapshot.commands());
            try {
                syncWorkflowCommands(path);
            } catch (RuntimeException failure) {
                LOG.warn("Plugin refresh kept the previous workflow generation: {}",
                    failure.getMessage());
            }
        }
        if (hookEngine != null) {
            hookEngine.setPluginHooks(snapshot.hooks());
        }
        reconcileMcpServers(snapshot.mcpServers());
    }

    /**
     * Adapts parsed plugin {@code commands/} entries for the model-facing
     * Skill tool. The command loader already resolved plugin/user-config
     * variables; invocation-time argument and session substitutions stay live
     * in the shared {@link com.claudecode.tools.skills.SkillToolProvider}
     * {@link SkillLoader} channel.
     */
    private static Skill asModelSkill(PluginCommandDefinition def) {
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("pluginCommand", true);
        frontmatter.put("argNames", def.argNames());
        frontmatter.put("disableModelInvocation", def.disableModelInvocation());
        frontmatter.put("hasUserSpecifiedDescription", def.hasUserSpecifiedDescription());
        if (def.whenToUse() != null) {
            frontmatter.put("whenToUse", def.whenToUse());
        }
        return new Skill(def.name(), def.description(), def.allowedTools(), def.prompt(),
            null, Skill.SkillSource.PLUGIN, def.model(), def.effort(), null, frontmatter);
    }

    /**
     * Diffs the desired {@code plugin:}-scoped MCP server set against what
     * this runtime previously connected: removed or configuration-changed
     * servers are disconnected and their {@code mcp__<server>__*} tools
     * evicted; new configurations are connected and their tools registered.
     * Equal configurations are left alone (no reconnect churn).
     */
    private void reconcileMcpServers(List<McpServerConfig> desired) {
        if (mcpClientManager == null) {
            return;
        }
        Map<String, McpServerConfig> desiredByName = desired.stream()
            .collect(Collectors.toMap(McpServerConfig::name, c -> c,
                (_, b) -> b, LinkedHashMap::new));

        for (Map.Entry<String, McpServerConfig> active
                : new ArrayList<>(activePluginServers.entrySet())) {
            McpServerConfig desiredConfig = desiredByName.get(active.getKey());
            if (active.getValue().equals(desiredConfig)) {
                continue;
            }
            disconnectPluginServer(active.getKey(), active.getValue());
        }

        for (McpServerConfig config : desiredByName.values()) {
            // putIfAbsent doubles as the claim check: a matching configuration
            // is already connected (or connecting); a changed one was evicted
            // in the preceding diff pass.
            if (activePluginServers.putIfAbsent(config.name(), config) != null) {
                continue;
            }
            // Async connect — plugin MCP servers must not block startup (or a
            // /reload-plugins) any more than user-configured ones do; tools
            // register into the live ToolRegistry once the handshake lands.
            Thread.ofVirtual().name("plugin-mcp-" + config.name()).start(() -> {
                Object lock = pluginMcpLocks.computeIfAbsent(config.name(), _ -> new Object());
                synchronized (lock) {
                    // A later reload may have removed/replaced this claim before
                    // this virtual thread got scheduled.
                    if (closed || !config.equals(activePluginServers.get(config.name()))) {
                        return;
                    }
                    try {
                        mcpClientManager.connect(config);
                        if (closed || !config.equals(activePluginServers.get(config.name()))) {
                            // A refresh waited for this same-server lock and
                            // will perform authoritative teardown next.
                            return;
                        }
                        if (toolRegistry != null) {
                            for (McpToolInfo info : mcpClientManager.listToolsForServer(config.name())) {
                                toolRegistry.register(new MCPTool(info, mcpClientManager));
                            }
                        }
                    } catch (Exception e) {
                        activePluginServers.remove(config.name(), config);
                        LOG.warn("Failed to connect plugin MCP server {}: {}",
                            config.name(), e.getMessage());
                    }
                }
            });
        }
    }

    /** Disconnects one exact MCP configuration generation and evicts its tools. */
    private void disconnectPluginServer(String name, McpServerConfig expectedConfig) {
        Object lock = pluginMcpLocks.computeIfAbsent(name, _ -> new Object());
        synchronized (lock) {
            if (!activePluginServers.remove(name, expectedConfig)) {
                return;
            }
            try {
                mcpClientManager.disconnect(name);
            } catch (Exception e) {
                LOG.warn("Failed to disconnect plugin MCP server {}: {}", name, e.getMessage());
            }
            if (toolRegistry != null) {
                String prefix = "mcp__" + name + "__";
                toolRegistry.unregisterMatching(toolName -> Strings.CS.startsWith(toolName, prefix));
            }
        }
    }


    private int countAgents() {
        try {
            return AgentDefinitionLoader.getAll(cwd).size();
        } catch (Exception _) {
            return current.agents().size();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Map.Entry<String, McpServerConfig> active
                : new ArrayList<>(activePluginServers.entrySet())) {
            disconnectPluginServer(active.getKey(), active.getValue());
        }
        postRefreshCallback = null;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("plugin runtime is closed");
    }
}
