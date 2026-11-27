package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.gateway.GatewayFileDiffPort;
import com.claudecode.gateway.GatewayPermissionModePort;
import com.claudecode.gateway.GatewaySessionActionsPort;
import com.claudecode.gateway.GatewaySessionCatalogPort;
import com.claudecode.gateway.GatewaySessionContextPort;
import com.claudecode.gateway.GatewaySessionMessagesPort;
import com.claudecode.gateway.GatewaySpinnerVerbsPort;
import com.claudecode.core.message.Message;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import com.claudecode.services.plugins.marketplace.PluginMarketplaceAdapter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.mcp.ManagedMcpRuntime;
import com.claudecode.tools.mcp.McpToolProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code claude web}: runs the single global web gateway as its own process.
 *
 * <p>The gateway lists every running TUI session through their Session Link
 * sockets. Only one instance exists per user: a live discovery record is reused,
 * and an exclusive file lock serializes concurrent starts so two invocations
 * cannot both bind a gateway.
 *
 * <p>Reuse is the reason {@code stop} and {@code status} exist. Attaching is a
 * silent success — same URL, same exit code as starting — so before these the
 * only way to tell whether an upgrade had taken effect was to guess. Both are
 * defined in {@link WebCliLifecycle}.
 */
@Command(
    name = "web",
    description = "Run the global web gateway that lists every running Claude Code session",
    mixinStandardHelpOptions = true,
    subcommands = {WebCliStopCommand.class, WebCliStatusCommand.class}
)
public class WebCliCommand implements Callable<Integer> {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The terminal this command was started from. This class is the composition
     * root for {@code claude web}, so binding to the process-global streams
     * happens here and nowhere else on this path; the URL it prints is the whole
     * point of the command.
     */
    private final CliOutput out = CliOutput.systemOut();
    private final CliOutput err = CliOutput.systemErr();

    @Option(names = {"-d", "--detach"},
        description = "Start the gateway in the background and return; end it with 'claude web stop'")
    boolean detach;

    /**
     * Runs {@code call()} and flushes the streams before its result decides the
     * process exit code.
     *
     * <p>{@code ClaudeCodeCli.main} ends with {@code System.exit(exitCode)},
     * which does not drain a {@code PrintStream}'s buffer. Every branch below
     * prints far less than the 8 KB capacity, so nothing is measurably lost
     * today — but the buffer is unbounded from this class's point of view, and
     * the cost of the guarantee is two virtual calls on one path. It is kept as
     * insurance rather than a fix for an observed failure.
     */
    @Override
    public Integer call() throws Exception {
        try {
            return run();
        } finally {
            out.flush();
            err.flush();
        }
    }

    private Integer run() throws Exception {
        Optional<WebGatewayRecord> running = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
        if (running.isPresent()) {
            WebCliLifecycle.describeReuse(running.get(), out);
            return 0;
        }
        if (detach) {
            // The child is a plain `claude web`: it takes the start lock and
            // hosts in the foreground of its own session, so concurrent starts
            // still converge on one gateway.
            Path log = ClaudePaths.WEB_GATEWAY_JSON.resolveSibling("web-gateway.log");
            return WebCliLifecycle.detach(
                new DetachedWebGateway(ClaudePaths.WEB_GATEWAY_JSON, log), log, out, err);
        }
        Path lockFile = ClaudePaths.WEB_GATEWAY_JSON.resolveSibling("web-gateway.lock");
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                return reuseWhenPublished();
            }
            Optional<WebGatewayRecord> racing = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
            if (racing.isPresent()) {
                WebCliLifecycle.describeReuse(racing.get(), out);
                return 0;
            }
            return hostUntilStopped();
        }
    }

    private Integer reuseWhenPublished() throws IOException, InterruptedException {
        Optional<WebGatewayRecord> published =
            WebGatewayRecord.awaitLive(ClaudePaths.WEB_GATEWAY_JSON, READY_TIMEOUT);
        if (published.isPresent()) {
            WebCliLifecycle.describeReuse(published.get(), out);
            return 0;
        }
        err.println("Another claude web is starting but did not publish its gateway in time.");
        return 1;
    }

    private Integer hostUntilStopped() throws Exception {
        try (ManagedMcpRuntime mcpRuntime = new McpToolProvider();
             CliResourceScope resources = new CliResourceScope()) {
            InteractionCoordinator interactions =
                new InteractionCoordinator(CliHeadlessSessionFactory::currentSessionIdOrEmpty);
            CliHeadlessGatewaySessions headless =
                WebHeadlessSessions.create(mcpRuntime, resources, interactions, err);
            String cwd = System.getProperty("user.dir");
            CustomModelCatalog modelCatalog = headless.modelCatalog();
            CliPluginRuntimeView pluginRuntime = headless.pluginRuntime();
            try (CliWebCronDaemon cronDaemon = new CliWebCronDaemon(headless, cwd);
                 CliCcConnectManager ccConnectManager = new CliCcConnectManager(cwd);
                 CliWebRuntimeControl runtimeControl = new CliWebRuntimeControl(
                     cronDaemon, modelCatalog, () -> {
                         ccConnectManager.reloadConfig();
                         if (pluginRuntime != null) {
                             pluginRuntime.refresh();
                         }
                     })) {
                cronDaemon.start();
                CommandRegistry commandRegistry = webCommands();
                if (pluginRuntime != null) {
                    pluginRuntime.attachCommandRegistry(commandRegistry);
                    pluginRuntime.reloadSkills();
                }
                var pluginMarketplace = PluginMarketplaceAdapter.standard(cwd,
                    () -> pluginRuntime != null
                        ? pluginRuntime.currentSnapshot().errors() : List.of(),
                    () -> pluginRuntime != null
                        ? pluginRuntime.currentSnapshot().mcpServers() : List.of());
                GatewaySessionMessagesPort sessionMessages = headless.messagesPort(cwd);
                CliGatewayRuntime runtime = new CliGatewayRuntime(
                    emptyRegistry(), new GatewaySessionCatalogPort() {}, headless,
                    interactions,
                    sessionMessages,
                    CliInteractiveSessionRunner.gatewaySettings(cwd),
                    cronDaemon.port(),
                CliInteractiveSessionRunner.gatewayModels(modelCatalog),
                CliGatewayPorts.commands(commandRegistry),
                sessionContext(headless, cwd),
                new GatewaySessionActionsPort() {},
                permissionMode(headless),
                new GatewaySpinnerVerbsPort() {},
                CliGatewayPorts.fileSuggestions(cwd),
                // Headless sessions opened inside claude web support interrupt/cancel;
                // remote TUI sessions are intercepted by GatewayServer's remote decorator first.
                CliInteractiveSessionRunner.gatewaySessionCancel(emptyRegistry(), headless),
                null,
                remoteFileDiff(sessionMessages),
                // Headless sessions resolve workspace files against their own projectPath;
                // remote TUI sessions are intercepted by RemoteWorkspaceFilePort first.
                new CliWorkspaceFileReader(sessionId ->
                    headless.projectPathOf(sessionId).map(Path::of)).port(),
                new CliBackgroundTasksPort(
                    TaskRegistry.global(),
                    headless::liveEngine,
                    headless::toolRegistry));
            runtime.setRuntimeControl(runtimeControl);
            runtime.setCcConnect(ccConnectManager);
            runtime.setPlugins(CliGatewayPorts.plugins(pluginMarketplace, pluginRuntime));
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                ccConnectManager.close();
                runtime.close();
                stopped.countDown();
            }));
                out.println(runtime.start().url());
                stopped.await();
                return 0;
            }
        }
    }

    /**
     * The changed-files source for every session a client can address.
     *
     * <p>{@link CliFileDiffAdapter} reads a transcript and nothing else, so the
     * only question is which directory holds it. A session this gateway opened
     * itself lives under its project; a session a TUI hosts lives under
     * <em>that</em> process's work directory, which this one did not know — so
     * every remote turn answered {@code no changes} however many files it had
     * written. Each worker publishes its work directory in its registration,
     * so the fallback resolves there, and only for a directory that actually
     * holds the transcript.
     */
    private static GatewayFileDiffPort remoteFileDiff(GatewaySessionMessagesPort transcript) {
        return new GatewayFileDiffPort() {
            @Override
            public Optional<ChangesSummary> summary(String sessionId, int turn) {
                return messagesOf(sessionId, transcript)
                    .flatMap(messages -> CliFileDiffAdapter.rootedAt(_ -> Optional.of(messages))
                        .summary(sessionId, turn));
            }

            @Override
            public Optional<FileDiff> diff(String sessionId, String path) {
                return messagesOf(sessionId, transcript)
                    .flatMap(messages -> CliFileDiffAdapter.rootedAt(_ -> Optional.of(messages))
                        .diff(sessionId, path));
            }
        };
    }

    /**
     * This session's transcript, read from whichever root actually holds it.
     *
     * <p>The messages port resolves an id against this process's own project
     * only, and the sessions a webui client addresses almost always belong to
     * a TUI running in some other project. Each such TUI publishes its work
     * directory in its discovery record, which is what the gateway's own
     * remote decorators read transcripts under, so the same rule is applied
     * here rather than a second one invented.
     */
    private static Optional<List<Message>> messagesOf(String sessionId,
            GatewaySessionMessagesPort transcript) {
        Optional<List<Message>> own = transcript.messages(sessionId, null);
        if (own.isPresent()) return own;
        return workers().stream()
            .map(WebWorkerRegistration::workDir)
            .flatMap(workDir -> transcript.messagesInWorkDir(sessionId, workDir).stream())
            .findFirst();
    }

    /** Every running worker whose discovery record this process can read. */
    private static List<WebWorkerRegistration> workers() {
        return new WebWorkerDirectory(ClaudePaths.WEB_WORKERS_DIR).scan();
    }

    /**
     * The model seat of every session this process opened itself. A session a
     * TUI hosts never reaches this port: the gateway's remote decorator routes
     * it over that TUI's Session Link first. What is left is this process's own
     * headless sessions, which carry live model and effort controllers — an
     * empty port here left a new web session's composer with no model to pick.
     *
     * <p>There is no TUI engine in this process, so the registry is empty and
     * the engine and {@code /context} collector are absent.
     */
    static GatewaySessionContextPort sessionContext(CliHeadlessGatewaySessions headless, String cwd) {
        return CliInteractiveSessionRunner.gatewaySessionContext(
            emptyRegistry(), null, headless, cwd, null);
    }

    /** The permission-mode seat of this process's own sessions, addressed as {@link #sessionContext}. */
    static GatewayPermissionModePort permissionMode(CliHeadlessGatewaySessions headless) {
        return CliInteractiveSessionRunner.gatewayPermissionMode(emptyRegistry(), headless);
    }

    /**
     * The webui's composer menu and {@code @} completion describe what this
     * host has installed, not what any one session is doing, so the gateway
     * answers them from this process rather than forwarding to a TUI. The
     * catalogue is the same default registry the TUI builds, which keeps the
     * two menus listing the same commands.
     *
     * <p>User-invocable skills are registered by the interactive startup path
     * (plugin/skill discovery needs a live plugin runtime), so a skill row is
     * absent here; the slash commands are not.
     */
    private static CommandRegistry webCommands() {
        return CommandFactory.createDefault(
            new CliSettingsManagementAdapter(),
            CliToolingCommandAdapter.create(TaskRegistry.global(), InvokedSkillRegistry.global()));
    }

    /**
     * The TUI sessions are listed through the worker directory and activated
     * through the TUI itself, so the standalone registry only refuses activation.
     * New sessions are created by the headless factory instead.
     */
    private static SessionHostRegistry emptyRegistry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "claude web cannot activate a TUI session; open it from the TUI"));
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    }
}
