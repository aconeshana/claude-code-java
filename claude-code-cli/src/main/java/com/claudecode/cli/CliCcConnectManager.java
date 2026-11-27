package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.ProcessUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayCcConnectPort;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervises per-TUI {@code cc-connect} IM collaboration sidecars inside the
 * standalone {@code claude web} daemon.
 *
 * <p>Each attached TUI gets its own lightweight {@code cc-connect} child process
 * connected directly to that TUI's Session Link Unix domain socket, preserving
 * full {@code sessionhost} semantics without modifying the bundled Go binary.
 */
final class CliCcConnectManager implements GatewayCcConnectPort, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CliCcConnectManager.class);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(8);
    private static final long HANDSHAKE_POLL_MS = 150L;
    private static final long WATCHDOG_FALLBACK_INTERVAL_MS = 15_000L;

    private final String defaultWorkDir;
    private volatile Path sourceConfig;
    private volatile RemoteBridge bridge;
    private final Map<Long, ManagedSidecar> sidecars = new ConcurrentHashMap<>();
    private final AtomicReference<Process> setupProcess = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch closeSignal = new CountDownLatch(1);
    private final Thread watchdog;

    CliCcConnectManager(String defaultWorkDir) {
        this(CcConnectSupport.resolveDefaultConfig(ClaudePaths.currentClaudeHome()), defaultWorkDir);
    }

    CliCcConnectManager(Path sourceConfig, String defaultWorkDir) {
        this.sourceConfig = sourceConfig;
        this.defaultWorkDir = StringUtils.isBlank(defaultWorkDir)
            ? System.getProperty("user.dir") : defaultWorkDir;
        this.watchdog = Thread.ofVirtual().name("cc-connect-watchdog").start(this::watchSidecars);
    }

    @Override
    public void bindRemoteBridge(RemoteBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onWorkerDisconnected(long pid) {
        if (closed.get() || pid <= 0 || !sidecars.containsKey(pid)) {
            return;
        }
        // Only tear down if the TUI process actually exited; a transient Session Link drop
        // (e.g. during /resume re-attach) while the process is still alive should keep the sidecar.
        if (isProcessAlive(pid)) {
            return;
        }
        Thread.ofVirtual().name("cc-connect-reap-" + pid).start(() -> {
            if (!closed.get() && stopAndCleanupIfPresent(pid)) {
                log.info("TUI pid={} disconnected and exited; stopped managed cc-connect sidecar", pid);
                notifyChanged();
            }
        });
    }

    /** Re-reads configuration path after an in-place Web runtime reload. */
    void reloadConfig() {
        sourceConfig = CcConnectSupport.resolveDefaultConfig(ClaudePaths.currentClaudeHome());
        notifyChanged();
    }

    @Override
    public Snapshot snapshot() {
        Path config = sourceConfig;
        boolean supported = CcConnectSupport.isPlatformSupported();
        boolean configured = CcConnectSupport.targetConfigured(config);
        boolean pending = CcConnectSupport.setupPending(config);
        List<String> channels = CcConnectSupport.configuredChannels(config);
        boolean setupActive = isSetupInProgress();

        RemoteBridge currentBridge = bridge;
        List<LiveWorker> workers = currentBridge == null ? List.of() : currentBridge.liveWorkers();
        List<TargetStatus> targets = new ArrayList<>(workers.size());
        for (LiveWorker worker : workers) {
            ManagedSidecar sidecar = sidecars.get(worker.pid());
            boolean alive = sidecar != null && sidecar.isAlive();
            String channel = sidecar != null ? sidecar.channel : "";
            String status = sidecar == null
                ? "stopped"
                : (alive ? sidecar.status : (StringUtils.isNotBlank(sidecar.lastError) ? "error" : "stopped"));
            String startedAt = sidecar != null && sidecar.startedAt != null
                ? sidecar.startedAt.toString() : "";
            String lastError = sidecar != null ? sidecar.lastError : "";

            // Reflect live TUI collaboration selection if the TUI already reports one.
            if (alive && StringUtils.isNotBlank(worker.sessionId())) {
                Optional<CollaborationState> live =
                    currentBridge.getCollaboration(worker.pid(), worker.sessionId());
                if (live.isPresent() && live.get().enabled()) {
                    channel = live.get().channel();
                    status = "connected";
                }
            }
            targets.add(new TargetStatus(
                worker.pid(),
                worker.workDir(),
                worker.sessionId(),
                worker.sessionTitle(),
                alive,
                channel,
                status,
                startedAt,
                lastError));
        }
        return new Snapshot(
            supported,
            configured,
            pending,
            config == null ? "" : config.toString(),
            channels,
            setupActive,
            targets);
    }

    @Override
    public synchronized Snapshot attach(AttachRequest request) {
        if (closed.get()) throw new IllegalStateException("CC Connector manager is closed");
        if (!CcConnectSupport.isPlatformSupported()) {
            throw new IllegalStateException("CC Connector requires macOS or Linux");
        }
        Path config = sourceConfig;
        if (!CcConnectSupport.targetConfigured(config)) {
            throw new IllegalStateException(
                "CC Connector is not configured yet; complete Feishu setup first");
        }
        LiveWorker worker = findWorker(request.pid(), request.sessionId())
            .orElseThrow(() -> new IllegalArgumentException(
                "No online TUI found for pid=" + request.pid() + " session=" + request.sessionId()));
        List<String> configuredChannels = CcConnectSupport.configuredChannels(config);
        String channel = StringUtils.isNotBlank(request.channel())
            ? request.channel().trim().toLowerCase(Locale.ROOT)
            : (configuredChannels.isEmpty() ? "feishu" : configuredChannels.getFirst());

        ManagedSidecar existing = sidecars.get(worker.pid());
        if (existing == null || !existing.isAlive()) {
            stopAndCleanup(worker.pid());
            existing = startSidecar(worker, config, channel);
            sidecars.put(worker.pid(), existing);
        } else {
            existing.channel = channel;
            existing.lastError = "";
        }

        boolean handshakeOk = awaitChannelHandshake(worker, existing, channel);
        if (!handshakeOk) {
            String detail = existing.lastError;
            if (StringUtils.isBlank(detail)) {
                detail = "cc-connect did not handshake channel '" + channel + "' in time";
            }
            existing.status = "error";
            existing.lastError = detail;
            notifyChanged();
            throw new IllegalStateException(detail);
        }

        RemoteBridge currentBridge = bridge;
        if (currentBridge != null && StringUtils.isNotBlank(worker.sessionId())) {
            currentBridge.setCollaboration(worker.pid(), worker.sessionId(), channel, true);
        }
        existing.status = "connected";
        existing.lastError = "";
        notifyChanged();
        return snapshot();
    }

    @Override
    public synchronized Snapshot detach(DetachRequest request) {
        long pid = request.pid();
        String sessionId = request.sessionId();
        if (pid <= 0 && StringUtils.isNotBlank(sessionId)) {
            pid = findWorker(0L, sessionId).map(LiveWorker::pid).orElse(0L);
        }
        if (pid <= 0) {
            throw new IllegalArgumentException("No matching TUI pid or session_id to detach");
        }
        RemoteBridge currentBridge = bridge;
        if (currentBridge != null) {
            try {
                currentBridge.setCollaboration(pid, sessionId, "", false);
            } catch (RuntimeException failure) {
                log.debug("Could not notify TUI pid={} of collaboration detach: {}",
                    pid, failure.getMessage());
            }
        }
        stopAndCleanup(pid);
        notifyChanged();
        return snapshot();
    }

    @Override
    public LogsResponse logs(long pid, int tail) {
        ManagedSidecar sidecar = sidecars.get(pid);
        if (sidecar == null || sidecar.logFile == null || !Files.isRegularFile(sidecar.logFile)) {
            return new LogsResponse(pid, List.of());
        }
        try {
            List<String> all = Files.readAllLines(sidecar.logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(1, tail));
            return new LogsResponse(pid, all.subList(from, all.size()));
        } catch (IOException _) {
            return new LogsResponse(pid, List.of());
        }
    }

    @Override
    public SetupResult setup(SetupRequest request, Consumer<String> progress) {
        if (request == null) throw new IllegalArgumentException("setup request is required");
        if (!CcConnectSupport.isPlatformSupported()) {
            throw new IllegalStateException("CC Connector setup requires macOS or Linux");
        }
        CollaborationSetupPort.Mode mode = parseMode(request.mode());
        char[] secret = request.appSecret();
        Path setupDir = null;
        try {
            if (isSetupInProgress()) {
                throw new IllegalStateException("Another Feishu setup is already in progress");
            }
            Path config = sourceConfig != null
                ? sourceConfig : ClaudePaths.currentClaudeHome().resolve("cc-connect.toml");
            Path credentialEnv = config.getParent().resolve("cc-connect.credentials.env");
            String workDir = StringUtils.isNotBlank(request.workDir())
                ? request.workDir() : defaultWorkDir;
            String project = CcConnectSupport.projectName(workDir);
            if (mode != CollaborationSetupPort.Mode.RESUME && (!Files.isRegularFile(config)
                    || CcConnectSupport.isLegacyUninitializedStarter(config, credentialEnv))) {
                CcConnectSupport.writeStarterConfig(config, project, workDir);
            }
            setupDir = Files.createTempDirectory("claude-code-feishu-setup-");
            CcConnectSupport.restrict(setupDir, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
            Path executable = CcConnectSupport.resolveExecutable(setupDir);
            ProcessBuilder builder = new ProcessBuilder(
                CcConnectSupport.setupCommand(executable, config, credentialEnv, project, mode));
            builder.directory(Path.of(workDir).toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            setupProcess.set(process);
            notifyChanged();
            if (mode == CollaborationSetupPort.Mode.BIND) {
                try (var out = process.getOutputStream();
                     var json = JsonUtils.getMapper().getFactory().createGenerator(out)) {
                    json.writeStartObject();
                    json.writeStringField("app_id",
                        request.appId() == null ? "" : request.appId());
                    json.writeFieldName("app_secret");
                    json.writeString(secret, 0, secret.length);
                    json.writeEndObject();
                    json.writeRaw('\n');
                }
            } else {
                process.getOutputStream().close();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 500) {
                    if (progress != null) progress.accept(line);
                }
            }
            int exit = process.waitFor();
            setupProcess.compareAndSet(process, null);
            if (exit != 0) {
                notifyChanged();
                return new SetupResult(false, "",
                    "Feishu setup did not complete (cc-connect exit " + exit + ")");
            }
            sourceConfig = config;
            notifyChanged();
            return new SetupResult(true, "feishu", "Feishu collaboration is ready");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            notifyChanged();
            return new SetupResult(false, "", "Feishu setup was interrupted");
        } catch (IOException failure) {
            notifyChanged();
            return new SetupResult(false, "",
                "Feishu setup failed: " + failure.getMessage());
        } finally {
            Arrays.fill(secret, '\0');
            request.clearAppSecret();
            setupProcess.set(null);
            deleteSetupDir(setupDir);
        }
    }

    @Override
    public boolean cancelSetup() {
        Process process = setupProcess.getAndSet(null);
        if (process != null && process.isAlive()) {
            process.destroy();
            notifyChanged();
            return true;
        }
        return false;
    }

    private ManagedSidecar startSidecar(LiveWorker worker, Path config, String channel) {
        Path runtimeDir = null;
        Path socketDir = null;
        try {
            runtimeDir = Files.createTempDirectory("claude-code-cc-connect-" + worker.pid() + "-");
            CcConnectSupport.restrict(runtimeDir, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
            socketDir = CcConnectSupport.createSocketDirectory();
            Path runtimeConfig = runtimeDir.resolve("cc-connect.toml");
            Files.copy(config, runtimeConfig, StandardCopyOption.REPLACE_EXISTING);
            CcConnectSupport.restrict(runtimeConfig, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            Path executable = CcConnectSupport.resolveExecutable(runtimeDir);
            Path logFile = runtimeDir.resolve("cc-connect.log");

            ProcessBuilder builder = new ProcessBuilder(
                executable.toString(), "--config", runtimeConfig.toString());
            Path workPath = Path.of(worker.workDir());
            if (Files.isDirectory(workPath)) {
                builder.directory(workPath.toFile());
            }
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().put("CC_SESSION_LINK_ENDPOINT", "unix://" + worker.socket());
            builder.environment().put("CC_SESSION_LINK_TOKEN", worker.token());
            builder.environment().put("CC_SESSION_WORK_DIR", worker.workDir());
            builder.environment().put("CC_CONNECT_API_SOCKET",
                socketDir.resolve("api.sock").toString());

            Process process = builder.start();
            ManagedSidecar managed = new ManagedSidecar(
                worker.pid(), channel, process, runtimeDir, socketDir, logFile, Instant.now());
            process.onExit().thenAccept(exited -> {
                if (!closed.get() && !managed.stopping.get()) {
                    managed.status = "error";
                    managed.lastError = "cc-connect exited with code " + exited.exitValue();
                    log.warn("Managed cc-connect sidecar for TUI pid={} exited with code {}; log={}",
                        worker.pid(), exited.exitValue(), logFile);
                    notifyChanged();
                }
            });
            log.info("Started managed cc-connect sidecar for TUI pid={} workDir={} channel={}",
                worker.pid(), worker.workDir(), channel);
            return managed;
        } catch (IOException failure) {
            cleanupDirs(runtimeDir, socketDir);
            throw new IllegalStateException("Failed to start cc-connect sidecar: " + failure.getMessage(), failure);
        }
    }

    private boolean awaitChannelHandshake(LiveWorker worker, ManagedSidecar sidecar, String channel) {
        RemoteBridge currentBridge = bridge;
        if (currentBridge == null) return true;
        long deadlineNanos = System.nanoTime() + HANDSHAKE_TIMEOUT.toNanos();
        while (true) {
            if (!sidecar.isAlive()) {
                sidecar.lastError = formatEarlyExitError(worker.pid());
                return false;
            }
            Optional<CollaborationState> state =
                currentBridge.getCollaboration(worker.pid(), worker.sessionId());
            if (state.isPresent() && state.get().availableChannels().contains(channel)) {
                return true;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                return false;
            }
            try {
                if (sidecar.process.waitFor(
                        Math.min(HANDSHAKE_POLL_MS, remainingMs), TimeUnit.MILLISECONDS)) {
                    sidecar.lastError = formatEarlyExitError(worker.pid());
                    return false;
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private String formatEarlyExitError(long pid) {
        List<String> tail = logs(pid, 10).lines();
        return tail.isEmpty()
            ? "cc-connect exited before completing Session Link handshake"
            : "cc-connect exited: " + tail.getLast();
    }

    private void watchSidecars() {
        while (!closed.get()) {
            try {
                if (closeSignal.await(WATCHDOG_FALLBACK_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            reapExitedTuis();
        }
    }

    private void reapExitedTuis() {
        boolean changed = false;
        for (Long pid : List.copyOf(sidecars.keySet())) {
            if (!isProcessAlive(pid) && stopAndCleanupIfPresent(pid)) {
                log.info("TUI pid={} exited; stopping managed cc-connect sidecar", pid);
                changed = true;
            }
        }
        if (changed) notifyChanged();
    }

    private static boolean isProcessAlive(long pid) {
        return ProcessUtils.isProcessRunning(pid);
    }

    private Optional<LiveWorker> findWorker(long pid, String sessionId) {
        RemoteBridge currentBridge = bridge;
        if (currentBridge == null) return Optional.empty();
        List<LiveWorker> workers = currentBridge.liveWorkers();
        if (pid > 0) {
            Optional<LiveWorker> byPid = workers.stream()
                .filter(worker -> worker.pid() == pid)
                .findFirst();
            if (byPid.isPresent()) return byPid;
        }
        if (StringUtils.isNotBlank(sessionId)) {
            return workers.stream()
                .filter(worker -> Strings.CS.equals(worker.sessionId(), sessionId))
                .findFirst();
        }
        return Optional.empty();
    }

    private void stopAndCleanup(long pid) {
        stopAndCleanupIfPresent(pid);
    }

    private boolean stopAndCleanupIfPresent(long pid) {
        ManagedSidecar sidecar = sidecars.remove(pid);
        if (sidecar == null) return false;
        sidecar.stopping.set(true);
        Process process = sidecar.process;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3_000, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2_000, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        cleanupDirs(sidecar.runtimeDir, sidecar.socketDir);
        return true;
    }

    private boolean isSetupInProgress() {
        Process process = setupProcess.get();
        return process != null && process.isAlive();
    }

    private void notifyChanged() {
        RemoteBridge currentBridge = bridge;
        if (currentBridge != null) {
            try {
                currentBridge.publishChanged();
            } catch (RuntimeException _) {
                // Best effort notification.
            }
        }
    }

    private static CollaborationSetupPort.Mode parseMode(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "create", "new" -> CollaborationSetupPort.Mode.CREATE;
            case "bind" -> CollaborationSetupPort.Mode.BIND;
            case "resume" -> CollaborationSetupPort.Mode.RESUME;
            default -> throw new IllegalArgumentException(
                "Unsupported setup mode: " + raw + " (expected create, bind, or resume)");
        };
    }

    private static void cleanupDirs(Path runtimeDir, Path socketDir) {
        if (socketDir != null) {
            try { Files.deleteIfExists(socketDir.resolve("api.sock")); } catch (IOException _) {}
            try { Files.deleteIfExists(socketDir); } catch (IOException _) {}
        }
        if (runtimeDir != null) {
            for (String name : List.of("cc-connect", "cc-connect.exe",
                    "cc-connect.toml", "cc-connect.log")) {
                try { Files.deleteIfExists(runtimeDir.resolve(name)); } catch (IOException _) {}
            }
            try { Files.deleteIfExists(runtimeDir); } catch (IOException _) {}
        }
    }

    private static void deleteSetupDir(Path setupDir) {
        if (setupDir == null) return;
        for (String name : List.of("cc-connect", "cc-connect.exe")) {
            try { Files.deleteIfExists(setupDir.resolve(name)); } catch (IOException _) {}
        }
        try { Files.deleteIfExists(setupDir); } catch (IOException _) {}
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeSignal.countDown();
        watchdog.interrupt();
        cancelSetup();
        for (Long pid : List.copyOf(sidecars.keySet())) {
            stopAndCleanup(pid);
        }
    }

    private static final class ManagedSidecar {
        final long pid;
        volatile String channel;
        final Process process;
        final Path runtimeDir;
        final Path socketDir;
        final Path logFile;
        final Instant startedAt;
        final AtomicBoolean stopping = new AtomicBoolean();
        volatile String status = "starting";
        volatile String lastError = "";

        ManagedSidecar(long pid, String channel, Process process,
                       Path runtimeDir, Path socketDir, Path logFile, Instant startedAt) {
            this.pid = pid;
            this.channel = channel;
            this.process = process;
            this.runtimeDir = runtimeDir;
            this.socketDir = socketDir;
            this.logFile = logFile;
            this.startedAt = startedAt;
        }

        boolean isAlive() {
            return process != null && process.isAlive();
        }
    }
}
