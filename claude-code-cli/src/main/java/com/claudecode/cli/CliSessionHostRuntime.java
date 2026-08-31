package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.sessionhost.*;
import com.claudecode.runtime.sessionlink.SessionLinkServer;
import com.claudecode.session.SessionSearch;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Owns the semantic Session Host and its collaboration sidecar. */
@Explanation("Supervises the collaboration sidecar as a semantic IM endpoint")
final class CliSessionHostRuntime implements AutoCloseable, CollaborationSetupPort {

    private static final Logger log = LoggerFactory.getLogger(CliSessionHostRuntime.class);
    private static final String CONFIG_ENV = "CLAUDE_CODE_IM_CONFIG";
    private static final String BINARY_ENV = "CLAUDE_CODE_CC_CONNECT_BINARY";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionHostRegistry registry;
    private final InteractionCoordinator interactions;
    private final SessionCollaborationController collaboration;
    private volatile Path sourceConfig;
    private final String workDir;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile Path runtimeDir;
    private volatile Path socketDir;
    private volatile SessionLinkServer server;
    private volatile Process sidecar;
    private volatile Process setupProcess;

    CliSessionHostRuntime(
            SessionHostRegistry registry,
            InteractionCoordinator interactions,
            SessionCollaborationController collaboration,
            Path sourceConfig,
            String workDir) {
        this.registry = registry;
        this.interactions = interactions;
        this.collaboration = collaboration;
        this.sourceConfig = sourceConfig;
        this.workDir = workDir;
    }

    static CliSessionHostRuntime prepare(
            QuerySession engine,
            AtomicReference<LanternaReplScreen> screenRef,
            String workDir) {
        InteractionCoordinator interactions =
            new InteractionCoordinator(() -> engine.conversation().getSessionId());
        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletionStage<SessionHostSession>
                    activate(SessionOpenRequest request) {
                try {
                    requireCurrentProject(workDir, request.workDir());
                } catch (IllegalArgumentException failure) {
                    return CompletableFuture.failedFuture(failure);
                }
                LanternaReplScreen screen = screenRef.get();
                if (screen == null) {
                    return CompletableFuture.failedFuture(
                        new IllegalStateException("interactive screen is not ready"));
                }
                return screen.activateHostSession(request);
            }

            @Override public List<SessionHostInfo> list() {
                return new SessionSearch(workDir).listSessions().stream().map(located -> {
                    var info = located.info();
                    String cwd = StringUtils.isBlank(info.cwd())
                        ? located.cwd() : info.cwd();
                    return new SessionHostInfo(info.id(), cwd, info.summary(),
                        Math.max(0, info.messageCount()), Instant.ofEpochMilli(info.lastModified()),
                        info.gitBranch());
                }).toList();
            }
        });
        SessionCollaborationController collaboration =
            new SessionCollaborationController(registry);
        Path claudeHome = ClaudePaths.currentClaudeHome();
        return new CliSessionHostRuntime(registry, interactions, collaboration,
            resolveConfig(System.getenv(CONFIG_ENV), claudeHome)
                .orElse(claudeHome.resolve("cc-connect.toml")), workDir);
    }

    SessionHostRegistry registry() { return registry; }
    InteractionCoordinator interactions() { return interactions; }
    SessionCollaborationController collaboration() { return collaboration; }
    boolean enabled() { return targetConfigured(sourceConfig); }

    @Override public boolean configured() { return enabled(); }

    @Override public boolean setupPending() {
        return sourceConfig != null && Files.isRegularFile(sourceConfig) && !enabled()
            && hasSavedFeishuCredentials(sourceConfig);
    }

    /** Starts the authenticated UDS before launching the sidecar process. */
    void start() {
        if (!enabled() || !started.compareAndSet(false, true)) return;
        try {
            Path dir = Files.createTempDirectory("claude-code-session-host-");
            restrict(dir, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            runtimeDir = dir;
            Path privateSocketDir = createSocketDirectory();
            socketDir = privateSocketDir;
            Path socket = privateSocketDir.resolve("link.sock");
            String token = newAuthToken();
            SessionLinkServer createdServer = new SessionLinkServer(
                new SessionLinkServer.Config(socket, token), registry, interactions,
                collaboration);
            createdServer.start();
            server = createdServer;

            Path runtimeConfig = dir.resolve("cc-connect.toml");
            Files.copy(sourceConfig, runtimeConfig,
                StandardCopyOption.REPLACE_EXISTING);
            restrict(runtimeConfig, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
            Path executable = resolveExecutable(dir);
            Path logFile = dir.resolve("cc-connect.log");
            ProcessBuilder builder = new ProcessBuilder(
                executable.toString(), "--config", runtimeConfig.toString());
            builder.directory(Path.of(workDir).toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            builder.environment().put("CC_SESSION_LINK_ENDPOINT", "unix://" + socket);
            builder.environment().put("CC_SESSION_LINK_TOKEN", token);
            builder.environment().put("CC_SESSION_WORK_DIR", workDir);
            builder.environment().put("CC_CONNECT_API_SOCKET",
                privateSocketDir.resolve("api.sock").toString());
            sidecar = builder.start();
            sidecar.onExit().thenAccept(process -> {
                if (started.get()) {
                    log.warn("cc-connect sidecar exited with code {}; log: {}",
                        process.exitValue(), logFile);
                }
            });
            log.info("Session Host IM endpoint started with config {}", sourceConfig);
        } catch (Exception failure) {
            started.set(false);
            cleanupEndpoint();
            throw new IllegalStateException("failed to start Session Host IM endpoint", failure);
        }
    }

    /** Creates a private short path that remains below macOS' Unix socket limit. */
    static Path createSocketDirectory() throws IOException {
        Path shortTempRoot = Path.of("/tmp");
        if (!Files.isDirectory(shortTempRoot)) {
            shortTempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        }
        Path dir = Files.createTempDirectory(shortTempRoot, "ccsh-");
        restrict(dir, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return dir;
    }

    private Path resolveExecutable(Path dir) throws IOException {
        String override = System.getenv(BINARY_ENV);
        if (StringUtils.isNotBlank(override)) {
            Path candidate = Path.of(override).toAbsolutePath().normalize();
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
                throw new IOException(BINARY_ENV + " is not an executable file: " + candidate);
            }
            return candidate;
        }
        String resource = nativeResource(
            System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
        try (InputStream input = CliSessionHostRuntime.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("bundled cc-connect is unavailable for " + resource);
            Path executable = dir.resolve(
                Strings.CS.endsWith(resource, ".exe") ? "cc-connect.exe" : "cc-connect");
            Files.copy(input, executable, StandardCopyOption.REPLACE_EXISTING);
            restrict(executable, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            return executable;
        }
    }

    static Optional<Path> resolveConfig(String explicit, Path claudeHome) {
        if (StringUtils.isNotBlank(explicit)) {
            Path selected = Path.of(explicit).toAbsolutePath().normalize();
            if (Files.exists(selected) && !Files.isRegularFile(selected)) {
                throw new IllegalArgumentException(CONFIG_ENV + " is not a regular file: " + selected);
            }
            return Optional.of(selected);
        }
        Path conventional = claudeHome.resolve("cc-connect.toml");
        return Files.isRegularFile(conventional) ? Optional.of(conventional) : Optional.empty();
    }

    static String nativeResource(String osName, String archName) {
        String os = osName.toLowerCase(Locale.ROOT);
        String platform;
        if (Strings.CS.contains(os, "mac")) {
            platform = "darwin";
        } else if (Strings.CS.contains(os, "linux")) {
            platform = "linux";
        } else {
            throw new IllegalArgumentException(
                "Session Host sidecar requires macOS or Linux Unix-domain sockets: " + osName);
        }
        String arch = archName.toLowerCase(Locale.ROOT);
        String normalizedArch;
        if (Strings.CS.equals("aarch64", arch) || Strings.CS.equals("arm64", arch)) {
            normalizedArch = "arm64";
        } else if (Strings.CS.equals("x86_64", arch) || Strings.CS.equals("amd64", arch)) {
            normalizedArch = "amd64";
        } else {
            throw new IllegalArgumentException("Unsupported Session Host architecture: " + archName);
        }
        return "/native/" + platform + "-" + normalizedArch + "/cc-connect";
    }

    static String newAuthToken() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    static void writeStarterConfig(Path config, String projectName,
                                   String workDir) throws IOException {
        Files.createDirectories(config.toAbsolutePath().getParent());
        String body = """
            language = "en"
            data_dir = "%s"

            [[projects]]
            name = "%s"

            [projects.agent]
            type = "sessionhost"

            [projects.agent.options]
            work_dir = "%s"
            auth_token_env = "CC_SESSION_LINK_TOKEN"
            request_timeout_seconds = 30
            max_frame_bytes = 16777216

            [[projects.platforms]]
            type = "feishu"

            [projects.platforms.options]
            thread_isolation = true
            enable_feishu_card = true
            progress_style = "card"
            """.formatted(
                toml(config.getParent().resolve("cc-connect-data").toString()),
                toml(projectName), toml(workDir));
        Files.writeString(config, body, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        restrict(config, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE));
    }

    static List<String> setupCommand(Path executable, Path config, Path credentialEnv,
                                     String project, Mode mode) {
        if (mode == Mode.RESUME) {
            return List.of(executable.toString(), "feishu", "resume",
                "--config", config.toString(), "--project", project, "--timeout", "600");
        }
        boolean bind = mode == Mode.BIND;
        var command = new ArrayList<>(List.of(executable.toString(), "feishu",
            bind ? "bind" : "new", "--config", config.toString(), "--project", project,
            "--credential-env", credentialEnv.toString(), "--discover-target", "--timeout", "600"));
        if (bind) command.add("--app-stdin");
        return List.copyOf(command);
    }

    @Override
    public CompletionStage<Result> setup(Request request, Consumer<String> progress) {
        if (request == null) return CompletableFuture.failedFuture(
            new IllegalArgumentException("setup request is required"));
        return CompletableFuture.supplyAsync(() -> {
            char[] secret = request.appSecret();
            try {
                if (started.get()) throw new IllegalStateException(
                    "Turn Collaboration off and restart before replacing its Feishu configuration");
                Path config = sourceConfig != null ? sourceConfig
                    : ClaudePaths.currentClaudeHome().resolve("cc-connect.toml");
                Path credentialEnv = config.getParent().resolve("cc-connect.credentials.env");
                String project = projectName(workDir);
                if (request.mode() != Mode.RESUME && (!Files.isRegularFile(config)
                        || isLegacyUninitializedStarter(config, credentialEnv))) {
                    writeStarterConfig(config, project, workDir);
                }
                Path setupDir = Files.createTempDirectory("claude-code-feishu-setup-");
                restrict(setupDir, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                Path executable = resolveExecutable(setupDir);
                ProcessBuilder builder = new ProcessBuilder(setupCommand(executable, config,
                    credentialEnv, project, request.mode()));
                builder.directory(Path.of(workDir).toFile());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                setupProcess = process;
                if (request.mode() == Mode.BIND) {
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
                    while ((line = reader.readLine()) != null && count++ < 400) {
                        if (progress != null) progress.accept(line);
                    }
                }
                int exit = process.waitFor();
                setupProcess = null;
                if (exit != 0) throw new IllegalStateException(
                    "Feishu setup did not complete (cc-connect exit " + exit + ")");
                sourceConfig = config;
                start();
                return new Result("feishu", "Feishu collaboration is ready");
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new CompletionException(failure);
            } finally {
                Arrays.fill(secret, '\0');
                request.clearAppSecret();
            }
        });
    }

    @Override public void cancel() {
        Process process = setupProcess;
        if (process != null && process.isAlive()) process.destroy();
    }

    private static String projectName(String cwd) {
        Path name = Path.of(cwd).toAbsolutePath().normalize().getFileName();
        String value = name == null ? "claude-code" : name.toString();
        value = value.replaceAll("[^A-Za-z0-9._-]+", "-");
        return StringUtils.isBlank(value) ? "claude-code" : value;
    }

    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    static boolean targetConfigured(Path config) {
        String text = readConfig(config);
        return hasAssignment(text, "bind_session_key") && hasAssignment(text, "allow_chat");
    }

    static boolean hasSavedFeishuCredentials(Path config) {
        String text = readConfig(config);
        if (!Strings.CS.contains(text, "type = \"feishu\"")
                && !Strings.CS.contains(text, "type = \"lark\"")) return false;
        String appId = assignmentValue(text, "app_id");
        String appSecret = assignmentValue(text, "app_secret");
        if (StringUtils.isBlank(appId) || StringUtils.isBlank(appSecret)) return false;
        return credentialValueAvailable(config, text, appId)
            && credentialValueAvailable(config, text, appSecret);
    }

    static boolean isLegacyUninitializedStarter(Path config, Path credentialEnv) {
        String text = readConfig(config);
        return !Files.isRegularFile(credentialEnv)
            && Strings.CS.contains(text, "type = \"sessionhost\"")
            && Strings.CS.equals(credentialEnv.toString(), assignmentValue(text, "env_file"))
            && Strings.CS.equals("${FEISHU_APP_ID}", assignmentValue(text, "app_id"))
            && Strings.CS.equals("${FEISHU_APP_SECRET}", assignmentValue(text, "app_secret"));
    }

    private static boolean credentialValueAvailable(Path config, String text, String value) {
        var placeholder = Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)}$")
            .matcher(value);
        if (!placeholder.matches()) return true;
        String name = placeholder.group(1);
        if (StringUtils.isNotBlank(System.getenv(name))) return true;
        String configuredEnvFile = assignmentValue(text, "env_file");
        if (StringUtils.isBlank(configuredEnvFile)) return false;
        Path envFile = Path.of(configuredEnvFile);
        if (!envFile.isAbsolute()) envFile = config.toAbsolutePath().getParent().resolve(envFile);
        String envText = readConfig(envFile.normalize());
        String savedValue = dotenvValue(envText, name);
        return StringUtils.isNotBlank(savedValue);
    }

    private static String assignmentValue(String text, String key) {
        var matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
            + "\\s*=\\s*\"([^\"]*)\"\\s*(?:#.*)?$").matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String dotenvValue(String text, String name) {
        var matcher = Pattern.compile("(?m)^\\s*(?:export\\s+)?" + Pattern.quote(name)
            + "\\s*=\\s*([^\\r\\n#]*)").matcher(text);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        if (value.length() >= 2
                && ((Strings.CS.startsWith(value, "\"") && Strings.CS.endsWith(value, "\""))
                    || (Strings.CS.startsWith(value, "'") && Strings.CS.endsWith(value, "'")))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.trim();
    }

    private static String readConfig(Path config) {
        if (config == null || !Files.isRegularFile(config)) return "";
        try { return Files.readString(config); }
        catch (IOException _) { return ""; }
    }

    private static boolean hasAssignment(String text, String key) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(key)
            + "\\s*=\\s*\\\"[^\\\"]+\\\"\\s*(?:#.*)?$").matcher(text).find();
    }

    /**
     * Enforces a process-per-project boundary for resumed sessions.
     * A Java runtime captures project settings, trust, tools, MCP, LSP, memory,
     * and transcript storage during composition; changing only cwd would mix
     * those dependencies across projects.
     */
    static void requireCurrentProject(String composedWorkDir, String requestedWorkDir) {
        if (StringUtils.isBlank(requestedWorkDir)) return;
        Path composed = canonicalDirectory(composedWorkDir, "composed project");
        Path requested = canonicalDirectory(requestedWorkDir, "requested project");
        if (!composed.equals(requested)) {
            throw new IllegalArgumentException(
                "This Claude Code process is bound to " + composed
                    + "; start another Claude Code process in " + requested
                    + " to open or resume that project");
        }
    }

    private static Path canonicalDirectory(String raw, String label) {
        try {
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException(label + " is not a directory: " + path);
            }
            return path.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot resolve " + label + ": " + raw, failure);
        }
    }

    private static void restrict(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException _) {
            // Windows has no POSIX permission view; its per-user temp directory
            // remains the applicable file-system boundary.
        }
    }

    @Override public void close() {
		cancel();
        if (started.get()) registry.endLocal("terminal_exit");
        started.set(false);
        cleanupEndpoint();
        interactions.close();
    }

    private void cleanupEndpoint() {
        Process process = sidecar;
        sidecar = null;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        SessionLinkServer link = server;
        server = null;
        if (link != null) link.close();
        Path sockets = socketDir;
        socketDir = null;
        if (sockets != null) {
            try { Files.deleteIfExists(sockets.resolve("link.sock")); } catch (IOException _) {}
            try { Files.deleteIfExists(sockets.resolve("api.sock")); } catch (IOException _) {}
            try { Files.deleteIfExists(sockets); } catch (IOException _) {}
        }
        Path dir = runtimeDir;
        runtimeDir = null;
        if (dir != null) {
            for (String name : List.of("cc-connect", "cc-connect.exe",
                    "cc-connect.toml", "cc-connect.log")) {
                try { Files.deleteIfExists(dir.resolve(name)); } catch (IOException _) {}
            }
            try { Files.deleteIfExists(dir); } catch (IOException _) {}
        }
    }
}
