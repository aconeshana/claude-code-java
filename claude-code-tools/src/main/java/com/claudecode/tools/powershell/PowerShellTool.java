package com.claudecode.tools.powershell;


import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.tasks.LocalShellTask;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskIdGenerator;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.text.StringUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.claudecode.tools.bash.BashTimeouts;
import com.claudecode.tools.bash.CommandSemantics;
import com.claudecode.tools.bash.ShellQuoteParse;
import com.claudecode.tools.monitor.MonitorFeatureGate;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.tasks.BackgroundTaskGate;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;

/**
 * PowerShellTool — execute PowerShell commands (Windows / cross-platform pwsh).
 */
@BuiltInTool(
    name = "PowerShell",
    strict = true,
    maxResultSizeChars = 30_000
)
public class PowerShellTool extends AnnotatedTool<JsonNode, Object> {


    @Override
    public String searchHint() {
        return "execute Windows PowerShell commands";
    }



    private static final int MAX_RESULT_CHARS = 30_000;
    private static final Set<String> TASK_ACTIVITY_SEARCH_CMDLETS = Set.of(
        "select-string", "get-childitem", "findstr", "where.exe");
    private static final Set<String> TASK_ACTIVITY_READ_CMDLETS = Set.of(
        "get-content", "get-item", "test-path", "resolve-path", "get-process",
        "get-service", "get-childitem", "get-location", "get-filehash", "get-acl",
        "format-hex");
    private static final Set<String> TASK_ACTIVITY_NEUTRAL_CMDLETS = Set.of(
        "write-output", "write-host");


    /** Native sandbox backend (selected by platform). Injected for tests. */
    private final SandboxManager sandboxManager;
    private final Function<String, String> envLookup;
    private final JsonNode inputSchema;

    public PowerShellTool() {
        this(SubprocessEnvironment::get, PlatformSandboxManager.create());
    }

    /** Test seam: inject a sandbox manager (e.g. NoopBackend). */
    PowerShellTool(SandboxManager sandboxManager) {
        this(SubprocessEnvironment::get, sandboxManager);
    }

    PowerShellTool(Function<String, String> envLookup, SandboxManager sandboxManager) {
        this.envLookup = envLookup != null ? envLookup : SubprocessEnvironment::get;
        this.inputSchema = buildSchema(BashTimeouts.maxTimeoutMs(this.envLookup));
        this.sandboxManager = sandboxManager;
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        String command = input.has("command") ? input.get("command").asText("") : "";
        return PowerShellPermissions.check(command, permCtx);
    }

    @Override public String description() {
// Implements  (canonical default branch).
        return PowerShellToolPrompt.getPrompt(envLookup);
    }


    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String requested = input == null ? "" : input.path("description").asText("");
        return org.apache.commons.lang3.StringUtils.isBlank(requested) ? "Run PowerShell command" : requested;
    }

    @Override public JsonNode inputSchema() { return inputSchema; }

    /** Released task-progress classification used by the teammate task board. */
    public static SearchReadClassification classifySearchOrReadCommand(String command) {
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) {
            return SearchReadClassification.NONE;
        }
        boolean search = false;
        boolean read = false;
        boolean classifiedAny = false;
        for (String segment : command.trim().split("\\s*[;|]\\s*")) {
            if (org.apache.commons.lang3.StringUtils.isBlank(segment)) continue;
            String[] words = segment.trim().split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) continue;
            String rawExecutable = words[0].toLowerCase(Locale.ROOT);
            String executable = Strings.CS.equals(rawExecutable, "where.exe")
                ? rawExecutable
                : PowerShellPermissions.resolveToCanonical(rawExecutable);
            if (TASK_ACTIVITY_NEUTRAL_CMDLETS.contains(executable)) continue;
            classifiedAny = true;
            boolean segmentSearch = TASK_ACTIVITY_SEARCH_CMDLETS.contains(executable);
            boolean segmentRead = TASK_ACTIVITY_READ_CMDLETS.contains(executable);
            if (!segmentSearch && !segmentRead) {
                return SearchReadClassification.NONE;
            }
            search |= segmentSearch;
            read |= segmentRead;
        }
        return classifiedAny
            ? new SearchReadClassification(search, read)
            : SearchReadClassification.NONE;
    }

    public record SearchReadClassification(boolean isSearch, boolean isRead) {
        private static final SearchReadClassification NONE =
            new SearchReadClassification(false, false);
    }

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        String command = input.has("command") ? input.get("command").asText("") : "";


        // Clamped to the documented max; values <=0 fall back to the default

        long defaultTimeoutMs = BashTimeouts.defaultTimeoutMs(envLookup);
        long maxTimeoutMs = BashTimeouts.maxTimeoutMs(envLookup);
        long timeoutMs = input.has("timeout") ? input.get("timeout").asLong(defaultTimeoutMs) : defaultTimeoutMs;
        if (timeoutMs <= 0) timeoutMs = defaultTimeoutMs;
        timeoutMs = Math.min(timeoutMs, maxTimeoutMs);
        boolean runInBackground = input.has("run_in_background") && input.get("run_in_background").asBoolean(false);
        boolean dangerouslyDisableSandbox =
            input.has("dangerouslyDisableSandbox") && input.get("dangerouslyDisableSandbox").asBoolean(false);


        // model already asked to background it.
        if (!runInBackground && !BackgroundTaskGate.disabled()
                && MonitorFeatureGate.systemEnabled()) {
            String sleepBlock = detectBlockedSleepPattern(command);
            if (sleepBlock != null) {
                return "Blocked: " + sleepBlock
                    + ". Run blocking commands in the background with run_in_background: true — "
                    + "you'll get a completion notification when done. For streaming events "
                    + "(watching logs, polling APIs), use the Monitor tool. If you genuinely need "
                    + "a delay (rate limiting, deliberate pacing), keep it under 2 seconds.";
            }
        }

        if (org.apache.commons.lang3.StringUtils.isBlank(command)) {
            return "Error: command is required";
        }

        // context is nullable at the API boundary; resolve a minimal fallback so
        // downstream dereferences (workingDirectory/sandboxConfig/abortController)
// don't NPE, matching BashTool.
        if (context == null) {
            context = ToolExecutionContext.of(new AbortController(), "unknown");
        }
        SandboxConfig sandboxConfig = context.sandboxConfig();


        // errorCode 11). The policy forbids unsandboxed commands but no Windows
        // sandbox backend exists → reject rather than silently run unsandboxed.
        if (Platform.CURRENT == Platform.WIN32 && sandboxConfig != null
                && sandboxConfig.enabled() && !sandboxConfig.allowUnsandboxedCommands()) {
            return "Error: sandbox mode is enabled but no Windows sandbox backend is available; "
                + "set sandbox.allowUnsandboxedCommands to true or disable the sandbox to run "
                + "PowerShell.";
        }

        SandboxDecision decision = sandboxManager.decide(command, dangerouslyDisableSandbox, sandboxConfig);
        if (decision.isReject()) {
            return "Error: " + decision.rejectReason();
        }

        try {
            if (runInBackground) {
                return executeBackgroundCommand(command, context, decision, sandboxConfig);
            }
            return executeCommand(command, timeoutMs, context, decision, sandboxConfig);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: command was interrupted";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Foreground execution: builds the PowerShell argv (sandbox-wrapped when
     * required), captures stdout/stderr, then applies the shared output pipeline
     * — image data-URI detection, inline truncation at {@link #MAX_RESULT_CHARS},
     * and large-output persistence (F7).
     */
    private Object executeCommand(String command, long timeoutMs,
                                   ToolExecutionContext context,
                                   SandboxDecision decision, SandboxConfig cfg) throws IOException, InterruptedException {
        String shell = getPowerShellPath();
        if (shell == null) {
            return "Error: PowerShell is not available on this system.";
        }
        List<String> argv = buildArgv(shell, command, context.workingDirectory(), decision, cfg);
        ProcessBuilder pb = new ProcessBuilder(argv);
        SubprocessEnvironment.applyTo(pb.environment());
        pb.directory(Path.of(context.workingDirectory()).toFile());
        pb.redirectErrorStream(false);
        if (decision.isSandboxed()) {
            pb.environment().putAll(sandboxManager.sandboxEnvironment(cfg));
        }

        Process process = pb.start();
        // Close child stdin so commands that probe stdin see EOF immediately.
        try { process.getOutputStream().close(); } catch (IOException _) {}

        context.abortController().onAbort(() -> {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        });

        StringUtils.EndTruncatingAccumulator stdout = new StringUtils.EndTruncatingAccumulator();
        StringUtils.EndTruncatingAccumulator stderr = new StringUtils.EndTruncatingAccumulator();

        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line + '\n');
                }
            } catch (IOException _) { }
        });

        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line + '\n');
                }
            } catch (IOException _) { }
        });

        boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

        if (!completed) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            stdoutThread.join(1000);
            stderrThread.join(1000);
            return "Error: command timed out after " + timeoutMs + " milliseconds\n" + stdout + stderr;
        }

        stdoutThread.join(5000);
        stderrThread.join(5000);

        int exitCode = process.exitValue();

// F7: image data-URI detection (matches BashTool.tryImageResult).
        String rawStdout = stdout.toString();
        ToolResult imageResult = tryImageResult(rawStdout);
        if (imageResult != null) {
            return imageResult;
        }

        String out = truncate(rawStdout, MAX_RESULT_CHARS);
        String err = truncate(stderr.toString(), MAX_RESULT_CHARS);

// F7: persist large output to disk (matches BashTool's large-output path).
        long rawTotal = rawStdout.length() + stderr.length();
        if (rawTotal > MAX_RESULT_CHARS) {
            Path toolResultsDir = new SessionManager(context.workingDirectory())
                .getToolResultsDir(context.sessionId());
            Files.createDirectories(toolResultsDir);
            Path outputFile = toolResultsDir.resolve(
                TaskIdGenerator.generate(TaskType.LOCAL_BASH) + ".txt");
            Files.writeString(outputFile, rawStdout + stderr, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            out = (out.isEmpty() ? "" : out + "\n")
                + "[Output truncated: " + rawTotal + " chars. Full output saved to: " + outputFile + "]";
        }

        StringBuilder result = new StringBuilder();
        if (!out.isEmpty()) result.append(out);
        if (!err.isEmpty()) {
            if (!result.isEmpty()) result.append('\n');
            result.append(err);
        }

        // every non-zero process exit as a failure in the model-visible text.
        CommandSemantics.Interpretation interpretation =
            CommandSemantics.powerShell(command, exitCode, out, err);
        if (interpretation.isError()) {
            result.append("\nExit code: ").append(exitCode);
        }
        return result.toString();
    }

    /**
     * Builds the argv to exec: non-sandboxed uses {@code -NoProfile
     * -NonInteractive -Command <command>}; sandboxed wraps a UTF-16LE base64
     * {@code -EncodedCommand} invocation through {@link SandboxManager#wrap}
     * (matches {@code powershellProvider.buildPowerShellArgs} +
     * {@code encodePowerShellCommand}).
     */
    private List<String> buildArgv(String shell, String command, String cwdStr,
                                   SandboxDecision decision, SandboxConfig cfg) {
        if (decision.isSandboxed()) {
            String encoded = encodeCommand(command);
            String psInvocation = shell + " -NoProfile -NonInteractive -EncodedCommand " + encoded;
            return sandboxManager.wrap(psInvocation, Path.of(cwdStr), cfg);
        }
        return List.of(shell, "-NoProfile", "-NonInteractive", "-Command", command);
    }

    /**
     * F3 (run_in_background): registers a real {@link TaskState} backed by
     * {@link LocalShellTask}, which streams the process output to the task's
     * {@code .output} file that {@code TaskOutputTool} reads back. The task runs
     * the PowerShell argv verbatim (no {@code bash -c} wrapping) via the
     * {@code explicitArgv} seam.
     */
    private String executeBackgroundCommand(String command,
                                             ToolExecutionContext context,
                                             SandboxDecision decision,
                                             SandboxConfig cfg) throws IOException {
        String shell = getPowerShellPath();
        if (shell == null) {
            return "Error: PowerShell is not available on this system.";
        }
        List<String> argv = buildArgv(shell, command, context.workingDirectory(), decision, cfg);
        TaskRegistry registry = TaskRegistry.global();
        // Reuse the LOCAL_BASH task type/schema for PowerShell background tasks
        // (TaskOutput reads the same .output layout regardless of shell).
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, command, context.agentId());
        LocalShellTask handle = new LocalShellTask(task, command, registry.store(),
            TaskOutputPaths.outputPath(task.id(), context), sandboxManager, decision, cfg,
            context.progressSink(), argv);

        try {
            handle.start(context.workingDirectory());
        } catch (IOException | RuntimeException e) {
            // The process never started: evict the just-created PENDING task.
            registry.store().remove(task.id());
            throw e;
        }
        registry.registerShell(handle);

        return "Command running in background with ID: " + task.id()
            + ". Output is being written to: " + handle.getOutputPath();
    }


    private ToolResult tryImageResult(String output) {
        String body = output.strip();
        Matcher m = DATA_URI_RE.matcher(body);
        if (!m.find()) return null;
        String mediaType = m.group(1);
        if (!Strings.CS.startsWith(mediaType, "image/")) return null;
        try {
            String b64 = m.group(2).replaceAll("\\s+", "");
            var rr = ImageResizer.maybeResizeAndDownsampleBase64(b64, mediaType);
            ObjectNode source = mapper().createObjectNode();
            source.put("type", "base64");
            source.put("media_type", rr.mediaType());
            source.put("data", Base64.getEncoder().encodeToString(rr.buffer()));
            return new ToolResult(List.of(new ImageBlock(source)), false);
        } catch (RuntimeException _) {
            return null;
        }
    }


    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n... [output truncated, " + s.length() + " chars total] ...";
    }

    // ── F8: PowerShell discovery (cached, snap-avoidant) ────────────────────

    /**
     * Cached resolved PowerShell binary. {@code null} = not yet probed; {@code ""}
     * = probed and not found (sentinel so we don't re-spawn on every call).
     */
    private static volatile String cachedPowerShellPath;

    /** Returns the cached PowerShell binary, probing once on first use (F8). */
    private static String getPowerShellPath() {
        String cached = cachedPowerShellPath;
        if (cached != null) return cached.isEmpty() ? null : cached;
        synchronized (PowerShellTool.class) {
            if (cachedPowerShellPath != null) {
                return cachedPowerShellPath.isEmpty() ? null : cachedPowerShellPath;
            }
            String resolved = resolvePowerShellPath();
            cachedPowerShellPath = (resolved == null) ? "" : resolved;
        }
        return cachedPowerShellPath.isEmpty() ? null : cachedPowerShellPath;
    }

    /** Test seam: clear the probed-path cache. */
    static void clearCachedPowerShellPath() {
        cachedPowerShellPath = null;
    }

    /** Test seam: returns the resolved (cached) PowerShell binary, or null if none. */
    static String resolvedPowerShellPathForTest() {
        return getPowerShellPath();
    }

    private static String resolvePowerShellPath() {
        String pwsh = probeExecutable("pwsh");
        if (pwsh != null) {

            if (Platform.CURRENT == Platform.LINUX) {
                String real = realpath(pwsh);
                if (Strings.CS.startsWith(pwsh, "/snap/") || (real != null && Strings.CS.startsWith(real, "/snap/"))) {
                    String direct = probeExecutable("/opt/microsoft/powershell/7/pwsh");
                    if (direct != null && !Strings.CS.startsWith(direct, "/snap/")) return direct;
                    direct = probeExecutable("/usr/bin/pwsh");
                    if (direct != null && !Strings.CS.startsWith(direct, "/snap/")) return direct;
                }
            }
            return pwsh;
        }
        return probeExecutable("powershell");
    }

    private static String probeExecutable(String exe) {
        try {
            Process p = new ProcessBuilder(probeCommand(exe)).start();
            try { p.getOutputStream().close(); } catch (IOException _) {}
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? exe : null;
        } catch (Exception _) {
            return null;
        }
    }

    static List<String> probeCommand(String exe) {
        return List.of(exe, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command",
            "$PSVersionTable.PSVersion.ToString()");
    }

    private static String realpath(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException _) {
            return null;
        }
    }

    // ── F6: start-sleep / sleep guard ──────────────────────────────────────


    private static String encodeCommand(String command) {
        return Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
    }

/** stdout image data-URI detector (matches BashTool DATA_URI_RE). */
    private static final Pattern DATA_URI_RE =
        Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

    /**
     * Detects a leading {@code start-sleep}/{@code sleep} of &ge; 2s that would
     * block the assistant. Only the first segment is inspected, and sub-2s sleeps
     * are allowed as legitimate pacing. Returns the reason or {@code null}.
     */
    private static final Pattern PS_SLEEP_PATTERN =
        Pattern.compile("^(?:start-sleep|sleep)(?:\\s+-s(?:econds)?)?\\s+(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    private static String detectBlockedSleepPattern(String command) {
        StringBuilder first = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        boolean afterSep = false;
        for (ShellQuoteParse.Token t : ShellQuoteParse.parse(command)) {
            if (t instanceof ShellQuoteParse.Op op) {
                String o = op.op();
                if (Strings.CS.equals(o, "|") || Strings.CS.equals(o, ";") || Strings.CS.equals(o, "&&") || Strings.CS.equals(o, "||")) {
                    if (afterSep && !rest.isEmpty()) rest.append(' ');
                    afterSep = true;
                    continue;
                }
                continue;
            } else if (t instanceof ShellQuoteParse.Word w) {
                if (afterSep) {
                    if (!rest.isEmpty()) rest.append(' ');
                    rest.append(w.value());
                } else {
                    if (!first.isEmpty()) first.append(' ');
                    first.append(w.value());
                }
            } else if (t instanceof ShellQuoteParse.Glob g) {
                if (afterSep) {
                    if (!rest.isEmpty()) rest.append(' ');
                    rest.append(g.pattern());
                } else {
                    if (!first.isEmpty()) first.append(' ');
                    first.append(g.pattern());
                }
            }
        }
        String firstSeg = first.toString().trim();
        Matcher m = PS_SLEEP_PATTERN.matcher(firstSeg);
        if (!m.find()) return null;
        int secs = Integer.parseInt(m.group(1));
        if (secs < 2) return null;
        String restText = rest.toString().trim();
        return restText.isEmpty() ? "standalone sleep " + secs : "sleep " + secs + " followed by: " + restText;
    }

/** Input-aware concurrency check: only read-only commands may run concurrently. */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        if (input == null || !input.has("command")) return false;
        if (input.has("run_in_background") && input.get("run_in_background").asBoolean(false)) {
            return false;
        }
        String command = input.get("command").asText("");
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) return false;
        return PowerShellPermissions.isReadOnlyCommand(command);
    }

    private static JsonNode buildSchema(long maxTimeoutMs) {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode commandProp = properties.putObject("command");
        commandProp.put("type", "string");
        commandProp.put("description", "The PowerShell command to execute");

        ObjectNode timeoutProp = properties.putObject("timeout");
        timeoutProp.put("type", "number");
        timeoutProp.put("description",
            "Optional timeout in milliseconds (max " + maxTimeoutMs + ")");

        ObjectNode descProp = properties.putObject("description");
        descProp.put("type", "string");
        descProp.put("description",
            "Clear, concise description of what this command does in active voice. "
            + "Never use words like \"complex\" or \"risk\" in the description - just "
            + "describe what it does.");

        if (!BackgroundTaskGate.disabled()) {
            ObjectNode bgProp = properties.putObject("run_in_background");
            bgProp.put("description", "Set to true to run this command in the background. Use Read to read the output later.");
            bgProp.put("type", "boolean");
        }

        ObjectNode sandboxProp = properties.putObject("dangerouslyDisableSandbox");
        sandboxProp.put("description",
            "Set this to true to dangerously override sandbox mode and run commands "
            + "without sandboxing.");
        sandboxProp.put("type", "boolean");

        ArrayNode required = schema.putArray("required");
        required.add("command");
        return schema;
    }
}
