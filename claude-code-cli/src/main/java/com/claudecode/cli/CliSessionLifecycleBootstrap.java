package com.claudecode.cli;

import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.engine.FileHistoryManager;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.services.agent.AgentSummaryService;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SettingsDiagnostics;
import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.services.plugins.runtime.PluginRuntimeSnapshot;
import com.claudecode.services.session.SessionFileHistorySink;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.session.TeamInfo;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.tasks.TeamRegistry;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.core.config.ClaudePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts lifecycle services after the engine configuration is frozen.
 *
 * <ul>
 *   <li>loads session and installed
 *       plugins into the already-registered tool, hook, and MCP runtimes.</li>
 *   <li>
 *        starts settings and
 *       flag-plugin reload listeners after their consumers exist.</li>
 *   <li>
 *        attaches transcript/file-history state
 *       and runs best-effort file-history cleanup once per launch.</li>
 * </ul>
 */
final class CliSessionLifecycleBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CliSessionLifecycleBootstrap.class);

    private CliSessionLifecycleBootstrap() {}

    static final class Lifecycle implements CliSessionLifecycleView, AutoCloseable {

        private final CliPluginRuntime pluginRuntimeOwner;
        private final SettingsReloadOrchestrator settingsReload;
        private final TranscriptRecorder transcriptRecorder;
        private final PromptInventory promptInventory;
        private final CliResourceScope resources;
        private Thread shutdownHook;

        Lifecycle(
                CliPluginRuntime pluginRuntimeOwner,
                SettingsReloadOrchestrator settingsReload,
                TranscriptRecorder transcriptRecorder,
                PromptInventory promptInventory,
                AutoCloseable flagPluginSubscription,
                CliResourceScope resources) {
            this.pluginRuntimeOwner = pluginRuntimeOwner;
            this.settingsReload = settingsReload;
            this.transcriptRecorder = transcriptRecorder;
            this.promptInventory = promptInventory;
            this.resources = Objects.requireNonNull(resources, "resources");
            if (pluginRuntimeOwner != null) resources.own(pluginRuntimeOwner);
            resources.own(settingsReload);
            resources.own(flagPluginSubscription);
        }

        @Override
        public CliPluginRuntimeView pluginRuntime() {
            return pluginRuntimeOwner;
        }

        @Override
        public SettingsReloadOrchestrator settingsReload() {
            return settingsReload;
        }

        @Override
        public TranscriptRecorder transcriptRecorder() {
            return transcriptRecorder;
        }

        @Override
        public PromptInventory promptInventory() {
            return promptInventory;
        }

        void installShutdownHook() {
            shutdownHook = new Thread(this::close, "cli-session-lifecycle-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }

        @Override
        public void close() {
            resources.close();
            Thread hook = shutdownHook;
            if (hook == null || hook == Thread.currentThread()) return;
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException _) {
                // JVM shutdown is already in progress.
            }
        }
    }

    record PromptInventory(
            CompletableFuture<PluginRuntimeSnapshot> pluginSnapshot,
            CompletableFuture<List<Skill>> skills,
            CompletableFuture<Void> ready,
            CliStartupTimeline timeline) {
    }

    static Lifecycle bootstrap(
            CliWorkspaceBootstrap.Workspace workspace,
            CliToolchainAssembler.Toolchain toolchain,
            CliEngineAssembler.EngineRuntime engineRuntime,
            McpRuntime mcpRuntime,
            AtomicReference<CliPluginRuntimeView> pluginRuntimeRef,
            CliResourceScope resources) {
        QuerySession engine = engineRuntime.engine();
        HookEngine hookEngine = workspace.hookEngine();
        boolean interactive = workspace.request().mode().interactive();

        registerAgentSummaryShutdown(toolchain.agentSummaryService());
        CliPluginRuntime pluginRuntime = createPluginRuntime(
            workspace, toolchain, engine, hookEngine, mcpRuntime);
        pluginRuntimeRef.set(pluginRuntime);
        toolchain.lspIntegration().attachDiagnostics(engine);
        CliStartupTimeline startupTimeline = new CliStartupTimeline();
        PromptInventory promptInventory = startPromptInventory(
            interactive, pluginRuntime, toolchain, workspace, engine, startupTimeline);
        if (interactive) engine.execution().addStartupBarrier(promptInventory.ready());

        SettingsReloadOrchestrator settingsReload = new SettingsReloadOrchestrator(
            toolchain.permissionGate(), hookEngine, System.getProperty("user.dir"), /* uiSink */ null,
            workspace.promptNonInteractive());
        AutoCloseable flagPluginSubscription = SettingsSources.subscribeFlagSettingsChanged(() -> {
            if (pluginRuntime == null) return;
            try {
                pluginRuntime.refresh();
            } catch (RuntimeException e) {
                log.warn("[STARTUP] Plugin refresh after flag settings change failed "
                        + "[sessionId={}, failureType={}]",
                    engine.conversation().getSessionId(), e.getClass().getName(),
                    ErrorUtils.redactedForLogging(e));
            }
        });
        Runnable startSettingsReload = () -> {
            try {
                settingsReload.start();
            } catch (IOException e) {
                log.warn("[STARTUP] Settings hot-reload disabled [cwd={}, failureType={}]",
                    workspace.cwd(), e.getClass().getName(),
                    ErrorUtils.redactedForLogging(e));
            }
        };
        if (interactive) {
            CliStartupTasks.run("settings-hot-reload-startup", startSettingsReload);
        } else {
            startSettingsReload.run();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            hookEngine.setForceSyncExecution(true);
            hookEngine.finalizePendingAsyncHooks();
        }, "async-hook-shutdown"));

        SessionManager transcriptSessionManager = new SessionManager(System.getProperty("user.dir"));
        TranscriptRecorder transcriptRecorder = new TranscriptRecorder(transcriptSessionManager);
        transcriptRecorder.setTeamInfoResolver(CliSessionLifecycleBootstrap::resolveTeamInfo);
        boolean persistSession = !workspace.request().session().noSessionPersistence();
        engine.execution().setTranscriptSink(persistSession ? transcriptRecorder : null);
        hookEngine.setPromptIdSupplier(() -> transcriptRecorder.currentPromptId(engine.conversation().getSessionId()));
        if (persistSession && engine.conversation().getFileHistoryManager() != null) {
            // Reads the recorder's live project so a cross-project resume moves the
            // file-history rows along with the transcript they annotate.
            engine.conversation().getFileHistoryManager().setSnapshotSink(
                new SessionFileHistorySink(new SessionStorage(), transcriptRecorder::sessionManager));
        }
        if (interactive) {
            CliStartupTasks.run("file-history-cleanup", CliSessionLifecycleBootstrap::cleanupFileHistory);
        } else {
            cleanupFileHistory();
        }

        Lifecycle lifecycle = new Lifecycle(pluginRuntime, settingsReload, transcriptRecorder,
            promptInventory, flagPluginSubscription, resources);
        lifecycle.installShutdownHook();
        return lifecycle;
    }

    private static String leadAgentName(String leadAgentId) {
        if (leadAgentId == null) return null;
        int separator = leadAgentId.indexOf('@');
        return separator < 0 ? leadAgentId : leadAgentId.substring(0, separator);
    }

    static TeamInfo resolveTeamInfo(String sessionId) {
        return TeamRegistry.instance().findByLeadSessionId(sessionId)
            .map(team -> new TeamInfo(team.name(), leadAgentName(team.leadAgentId())))
            .orElse(TeamInfo.EMPTY);
    }

    private static CliPluginRuntime createPluginRuntime(
            CliWorkspaceBootstrap.Workspace workspace,
            CliToolchainAssembler.Toolchain toolchain,
            QuerySession engine,
            HookEngine hookEngine,
            McpRuntime mcpRuntime) {
        try {
            PluginDirectories pluginDirs = PluginDirectories.standard();
            PluginRuntimeLoader pluginLoader = new PluginRuntimeLoader(
                pluginDirs,
                PluginSettingsStore.standard(workspace.cwd()),
                new InstalledPluginsStore(pluginDirs.installedPluginsFile()),
                () -> engine.conversation().getSessionId(),
                toolchain.inlinePluginPaths(), toolchain.inlinePluginPathsWithoutMcp());
            CliPluginRuntime pluginRuntime = new CliPluginRuntime(pluginLoader, workspace.cwd(),
                toolchain.skillToolProvider().getSkillLoader(), hookEngine,
                mcpRuntime.clientRuntime(), toolchain.toolRegistry());
            Path userDir = Path.of(System.getProperty("user.dir"));
            pluginRuntime.setPostRefreshCallback(snapshot ->
                CliStartupTasks.run("lsp-plugin-refresh", () ->
                    toolchain.lspIntegration().applySnapshot(userDir, snapshot, engine)));
            toolchain.lspIntegration().setPluginRefresh(pluginRuntime::refresh);
            return pluginRuntime;
        } catch (Exception e) {
            log.warn("[STARTUP] Plugin runtime initialization failed "
                    + "[cwd={}, sessionId={}, failureType={}]",
                workspace.cwd(), engine.conversation().getSessionId(), e.getClass().getName(),
                ErrorUtils.redactedForLogging(e));
            return null;
        }
    }

    private static PluginRuntimeSnapshot loadPlugins(CliPluginRuntime pluginRuntime) {
        try {
            var pluginSnapshot = pluginRuntime.loadAndInject();
            if (pluginSnapshot.enabledCount() > 0 || !pluginSnapshot.errors().isEmpty()) {
                log.info("Plugin runtime: {} plugins, {} commands, {} agents, {} hooks, {} MCP servers, {} errors",
                    pluginSnapshot.enabledCount(), pluginSnapshot.commands().size(),
                    pluginSnapshot.agents().size(), pluginSnapshot.hookCommandCount(),
                    pluginSnapshot.mcpServers().size(), pluginSnapshot.errors().size());
            }
            return pluginSnapshot;
        } catch (Exception e) {
            log.warn("[STARTUP] Plugin runtime loading failed [failureType={}]",
                e.getClass().getName(), ErrorUtils.redactedForLogging(e));
            return PluginRuntimeSnapshot.empty();
        }
    }

    private static PromptInventory startPromptInventory(
            boolean interactive, CliPluginRuntime pluginRuntime,
            CliToolchainAssembler.Toolchain toolchain,
            CliWorkspaceBootstrap.Workspace workspace, QuerySession engine,
            CliStartupTimeline timeline) {
        Supplier<PluginRuntimeSnapshot> loadPluginSnapshot = () -> {
            PluginRuntimeSnapshot snapshot = pluginRuntime == null
                ? PluginRuntimeSnapshot.empty() : loadPlugins(pluginRuntime);
            timeline.mark("plugin");
            timeline.mark("hooks");
            return snapshot;
        };
        Supplier<List<Skill>> loadSkills = () -> {
            try {
                List<Skill> loaded = toolchain.skillToolProvider().getSkillLoader().loadAll();
                log.info("Skill inventory ready: {} skills loaded", loaded.size());
                return List.copyOf(loaded);
            } catch (RuntimeException error) {
                log.warn("[STARTUP] Skill inventory loading failed [cwd={}, failureType={}]",
                    workspace.cwd(), error.getClass().getName(),
                    ErrorUtils.redactedForLogging(error));
                return List.of();
            } finally {
                timeline.mark("skills");
            }
        };
        if (!interactive) {
            PluginRuntimeSnapshot snapshot = loadPluginSnapshot.get();
            toolchain.lspIntegration().applySnapshot(
                Path.of(workspace.cwd()), snapshot, engine);
            List<Skill> skills = loadSkills.get();
            return new PromptInventory(
                CompletableFuture.completedFuture(snapshot),
                CompletableFuture.completedFuture(skills),
                CompletableFuture.completedFuture(null), timeline);
        }
        CompletableFuture<PluginRuntimeSnapshot> plugins = CliStartupTasks.supply(
            "plugin-inventory-startup", loadPluginSnapshot);
        CompletableFuture<List<Skill>> skills = plugins.thenCompose(_ ->
            CliStartupTasks.supply("skill-inventory-startup", loadSkills));
        plugins.thenAccept(snapshot -> CliStartupTasks.run("lsp-snapshot-startup", () ->
            toolchain.lspIntegration().applySnapshot(
                Path.of(workspace.cwd()), snapshot, engine)));
        return new PromptInventory(
            plugins, skills, CompletableFuture.allOf(plugins, skills), timeline);
    }

    private static void registerAgentSummaryShutdown(AgentSummaryService agentSummaryService) {
        if (agentSummaryService != null) {
            Runtime.getRuntime().addShutdownHook(
                new Thread(agentSummaryService::close, "agent-summary-shutdown"));
        }
    }

    private static void cleanupFileHistory() {
        if (SettingsDiagnostics.shouldSkipFileHistoryCleanup()) {
            log.debug("Skipping file-history cleanup: settings have validation errors but cleanupPeriodDays was explicitly set");
            return;
        }
        try {
            FileHistoryManager.cleanupOldBackups(
                ClaudePaths.CLAUDE_HOME.resolve("file-history"), RuntimeSettings.loadCleanupPeriodDays());
            int removedWorktrees = WorktreeService.cleanupStaleAgentWorktrees(
                Instant.now().minus(Duration.ofDays(RuntimeSettings.loadCleanupPeriodDays())));
            if (removedWorktrees > 0) {
                log.info("Removed {} stale temporary worktree(s)", removedWorktrees);
            }
        } catch (Exception e) {
            log.warn("[STARTUP] Startup cleanup failed [path={}, failureType={}]",
                ClaudePaths.CLAUDE_HOME.resolve("file-history"), e.getClass().getName(),
                ErrorUtils.redactedForLogging(e));
        }
    }
}
