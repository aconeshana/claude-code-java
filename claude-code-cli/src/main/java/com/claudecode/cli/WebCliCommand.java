package com.claudecode.cli;

import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.core.config.ClaudePaths;
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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/**
 * {@code claude web}: runs the single global web gateway as its own process.
 *
 * <p>The gateway lists every running TUI session through their Session Link
 * sockets. Only one instance exists per user: a live discovery record is reused,
 * and an exclusive file lock serializes concurrent starts so two invocations
 * cannot both bind a gateway.
 */
@Command(
    name = "web",
    description = "Run the global web gateway that lists every running Claude Code session",
    mixinStandardHelpOptions = true
)
public class WebCliCommand implements Callable<Integer> {

    private static final long READY_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_MILLIS = 100;

    @Override
    public Integer call() throws Exception {
        Optional<WebGatewayRecord> running = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
        if (running.isPresent()) {
            System.out.println(running.get().url());
            return 0;
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
                System.out.println(racing.get().url());
                return 0;
            }
            return hostUntilStopped();
        }
    }

    private Integer reuseWhenPublished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<WebGatewayRecord> published = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
            if (published.isPresent()) {
                System.out.println(published.get().url());
                return 0;
            }
            Thread.sleep(POLL_MILLIS);
        }
        System.err.println("Another claude web is starting but did not publish its gateway in time.");
        return 1;
    }

    private Integer hostUntilStopped() throws Exception {
        try (ManagedMcpRuntime mcpRuntime = new McpToolProvider();
             CliResourceScope resources = new CliResourceScope()) {
            CliHeadlessGatewaySessions headless = WebHeadlessSessions.create(mcpRuntime, resources);
            String cwd = System.getProperty("user.dir");
            CommandRegistry commandRegistry = webCommands();
            GatewaySessionMessagesPort sessionMessages = headless.messagesPort(cwd);
            CliGatewayRuntime runtime = new CliGatewayRuntime(
                emptyRegistry(), new GatewaySessionCatalogPort() {}, headless,
                // This process raises no interaction of its own: every permission ask it
                // serves belongs to a TUI session and is forwarded over that session's
                // Session Link, which carries its own id. The supplier is therefore never
                // consulted, and a local ask would be a defect rather than a mode the
                // gateway should silently attribute to an empty session.
                new InteractionCoordinator(() -> {
                    throw new IllegalStateException(
                        "the web gateway raises no local interaction to attribute a session to");
                }),
                sessionMessages,
                CliInteractiveSessionRunner.gatewaySettings(cwd),
                null,
                CliInteractiveSessionRunner.gatewayModels(new CustomModelJsonStore()),
                CliGatewayPorts.commands(commandRegistry),
                // This process runs no engine, so its own context port answers
                // nothing. Every session a webui client can address belongs to
                // a TUI, and the gateway answers each of them from its owner's
                // transcript and Session Link through its own remote
                // decorator — building the same answers here would resolve
                // them against this process's project directory instead.
                new GatewaySessionContextPort() {},
                new GatewaySessionActionsPort() {},
                new GatewayPermissionModePort() {},
                new GatewaySpinnerVerbsPort() {},
                CliGatewayPorts.fileSuggestions(cwd),
                // sessionCancel / taskBoard stay null on purpose: both are
                // answered for every addressable session by the gateway's own
                // remote decorators, which the interactive runtime installs
                // when it hosts this gateway. This process has no local
                // sessions of its own to cancel or list.
                null, null,
                // The changed-files card needs no live engine: CliFileDiffAdapter
                // derives every turn's hunks from the transcript. It does need
                // the right transcript, and the session a client asks about is
                // usually one a TUI in another project hosts — this process's
                // own messages port resolves ids against its own project only,
                // and answered "no changes" for every remote turn. The fallback
                // resolves a remote session's transcript under the directory
                // its owning worker reported, the same rule the gateway's own
                // messages snapshot follows.
                remoteFileDiff(sessionMessages),
                // workspaceFile stays null: RemoteWorkspaceFilePort resolves
                // every remote session's files through its owner's reported
                // work directory, which is the only correct root here.
                null);
            CountDownLatch stopped = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
                runtime.close();
                stopped.countDown();
            }));
            System.out.println(runtime.start().url());
            stopped.await();
            return 0;
        }
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
