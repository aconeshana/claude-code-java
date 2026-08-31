package com.claudecode.tools.bash;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FileStateCache;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.io.FileTextUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.imagestore.ImageResizer;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.ProcessTreeTerminator;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.StringUtils;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.hints.ClaudeCodeHint;
import com.claudecode.tools.hints.ClaudeCodeHintStore;
import com.claudecode.tools.hints.ClaudeCodeHints;
import com.claudecode.tools.hints.ClaudeCodeHints.HintExtraction;
import com.claudecode.tools.monitor.MonitorFeatureGate;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.shell.OutputLimits;
import com.claudecode.tools.tasks.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shell command execution tool.
 */
@BuiltInTool(
    name = "Bash",
    strict = true,
    maxResultSizeChars = 30_000
)
public class BashTool extends AnnotatedTool<JsonNode, Object> {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);


    @Override
    public String searchHint() {
        return "execute shell commands";
    }



    private static final long PROGRESS_THRESHOLD_MS = 2_000L;
    private static final long PROGRESS_INTERVAL_MS = 1_000L;

    private static final String GIT_INSTRUCTIONS_MARKER =
        "\n\n\n# Committing changes with git\n";

    /** Maximum output size before persisting to disk (64MB). */
    private static final int MAX_OUTPUT_SIZE = StringUtils.MAX_STRING_LENGTH;

    /** Maximum output lines to return inline. */
    private static final int MAX_INLINE_LINES = 10000;

    // Read-only / search commands that don't modify the filesystem
    private static final Set<String> SEARCH_READ_COMMANDS = Set.of(
        "grep", "egrep", "fgrep", "rg", "ag", "ack",
        "find", "fd", "locate",
        "ls", "dir", "tree", "exa",
        "cat", "bat", "less", "more", "head", "tail",
        "wc", "file", "which", "whereis", "whence", "type",
        "stat", "du", "df",
        "echo", "printf",
        "diff", "comm", "sort", "uniq", "cut", "tr", "awk", "sed",
        "jq", "yq", "xmllint",
        "git log", "git show", "git diff", "git status", "git branch",
        "git tag", "git remote", "git rev-parse", "git ls-files",
        "git blame", "git shortlog",
        "pwd", "env", "printenv", "id", "whoami", "hostname", "uname",
        "date", "cal"
    );

    private static final Set<String> TASK_ACTIVITY_SEARCH_COMMANDS = Set.of(
        "find", "grep", "rg", "ag", "ack", "locate", "which", "whereis");
    private static final Set<String> TASK_ACTIVITY_READ_COMMANDS = Set.of(
        "cat", "head", "tail", "less", "more", "wc", "stat", "file", "strings",
        "jq", "awk", "cut", "sort", "uniq", "tr");
    private static final Set<String> TASK_ACTIVITY_LIST_COMMANDS = Set.of(
        "ls", "tree", "du");

    // Task 56.8: Git operation patterns
    private static final Set<String> GIT_WRITE_COMMANDS = Set.of(
        "git commit", "git push", "git pull", "git merge", "git rebase",
        "git reset", "git checkout", "git switch", "git stash",
        "git clean", "git rm", "git mv", "git tag -d",
        "git branch -d", "git branch -D"
    );

    /** Commands whose successful execution normally produces no stdout. */
    private static final Set<String> SILENT_COMMANDS = Set.of(
        "mv", "cp", "rm", "mkdir", "rmdir", "chmod", "chown", "chgrp",
        "touch", "ln", "cd", "export", "unset", "wait");

    /** Output/status-only commands ignored after a fallback operator. */
    private static final Set<String> SEMANTIC_NEUTRAL_COMMANDS = Set.of(
        "echo", "printf", "true", "false", ":");

/**
     * Pattern to detect trailing incomplete operators.
     */
    private static final Pattern INCOMPLETE_COMMAND_PATTERN =
        Pattern.compile("(\\|\\s*|&&\\s*|\\|\\|\\s*)$");

    /**
     * Leading {@code sleep N} (integer seconds) guard.
     */
    private static final Pattern SLEEP_PATTERN = Pattern.compile("^sleep\\s+(\\d+)\\s*$");

    // Task 56.9: Claude Code hints — the parser + single-slot store live in

    // Hints are STRIPPED from tool output (the model never sees the raw tag) and
    // surfaced to the user as a plugin-install prompt; they are NOT appended to
    // the model-visible output.


    // is an image data URI is returned as a real image block, not a text note).
    private static final Pattern DATA_URI_RE =
        Pattern.compile("^data:([^;]+);base64,(.+)$", Pattern.DOTALL);

    /** Env lookup seam for {@code BASH_MAX_OUTPUT_LENGTH} — see {@link OutputLimits}. */
    private final Function<String, String> envLookup;
    private final JsonNode inputSchema;
    private final Supplier<Boolean> includeGitInstructionsSupplier;
    private final BiFunction<String, String, Path> toolResultsDirectoryResolver;

    /** Native sandbox backend (selected by platform). Injected for tests. */
    private final SandboxManager sandboxManager;
    private volatile Supplier<String> modelSupplier = () -> ModelNames.DEFAULT_MAIN_LOOP_MODEL;
    private volatile Supplier<JsonNode> attributionSettingsSupplier = () -> null;
    private volatile SudoPasswordInteraction sudoPasswordInteraction =
        SudoPasswordInteraction.UNAVAILABLE;

    public BashTool() {
        this(SubprocessEnvironment::get, PlatformSandboxManager.create(), () -> true);
    }

    /** Test seam: inject an env lookup instead of the real process environment. */
    public BashTool(Function<String, String> envLookup) {
        this(envLookup, PlatformSandboxManager.create(), () -> true);
    }

    /** Test seam: inject both an env lookup and a sandbox manager (e.g. NoopBackend). */
    public BashTool(Function<String, String> envLookup, SandboxManager sandboxManager) {
        this(envLookup, sandboxManager, () -> true);
    }

    public BashTool(Function<String, String> envLookup, SandboxManager sandboxManager,
             Supplier<Boolean> includeGitInstructionsSupplier) {
        this(envLookup, sandboxManager, includeGitInstructionsSupplier,
            (cwd, sessionId) -> new SessionManager(cwd).getToolResultsDir(sessionId));
    }

    BashTool(Function<String, String> envLookup, SandboxManager sandboxManager,
             Supplier<Boolean> includeGitInstructionsSupplier,
             BiFunction<String, String, Path> toolResultsDirectoryResolver) {
        this.envLookup = envLookup != null ? envLookup : SubprocessEnvironment::get;
        this.inputSchema = buildSchema(BashTimeouts.maxTimeoutMs(this.envLookup));
        this.sandboxManager = sandboxManager != null
            ? sandboxManager : PlatformSandboxManager.create();
        this.includeGitInstructionsSupplier = includeGitInstructionsSupplier != null
            ? includeGitInstructionsSupplier : () -> true;
        this.toolResultsDirectoryResolver = toolResultsDirectoryResolver != null
            ? toolResultsDirectoryResolver
            : (cwd, sessionId) -> new SessionManager(cwd).getToolResultsDir(sessionId);
    }

    /** Injects the live main-loop model used by the description's attribution. */
    public void setModelSupplier(Supplier<String> modelSupplier) {
        this.modelSupplier = modelSupplier != null
            ? modelSupplier : () -> ModelNames.DEFAULT_MAIN_LOOP_MODEL;
    }


    public void setAttributionSettingsSupplier(Supplier<JsonNode> attributionSettingsSupplier) {
        this.attributionSettingsSupplier = attributionSettingsSupplier != null
            ? attributionSettingsSupplier : () -> null;
    }

    /** Installs the local-only phase-one sudo credential prompt. */
    public void setSudoPasswordInteraction(SudoPasswordInteraction interaction) {
        sudoPasswordInteraction = interaction != null
            ? interaction : SudoPasswordInteraction.UNAVAILABLE;
    }

    @Override
    public String description() {

        String template = ToolTexts.description("Bash");
        if (!includeGitInstructions()) {
            int optionalSection = template.indexOf(GIT_INSTRUCTIONS_MARKER);
            if (optionalSection >= 0) {
                template = template.substring(0, optionalSection + 1);
            }
        }
        AttributionTexts attribution = attributionTexts();
        long defaultTimeoutMs = BashTimeouts.defaultTimeoutMs(envLookup);
        long maxTimeoutMs = BashTimeouts.maxTimeoutMs(envLookup);
        return applyAttribution(template, attribution)
            .replace("up to 600000ms / 10 minutes", "up to " + maxTimeoutMs
                + "ms / " + (maxTimeoutMs / 60_000) + " minutes")
            .replace("after 120000ms (2 minutes)", "after " + defaultTimeoutMs
                + "ms (" + (defaultTimeoutMs / 60_000) + " minutes)");
    }


    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String requested = input == null ? "" : input.path("description").asText("");
        return org.apache.commons.lang3.StringUtils.isBlank(requested) ? "Run shell command" : requested;
    }

    private boolean includeGitInstructions() {
        try {
            return !Boolean.FALSE.equals(includeGitInstructionsSupplier.get());
        } catch (RuntimeException _) {
            return true;
        }
    }

    private AttributionTexts attributionTexts() {
        String defaultCommit = "Co-Authored-By: " + attributionModelName(modelSupplier.get())
            + " <noreply@anthropic.com>";
        String defaultPr = "🤖 Generated with [Claude Code](https://claude.com/claude-code)";
        JsonNode settings;
        try {
            settings = attributionSettingsSupplier.get();
        } catch (RuntimeException _) {
            settings = null;
        }
        JsonNode configured = settings == null ? null : settings.get("attribution");
        if (configured != null && configured.isObject()) {
            return new AttributionTexts(
                configured.hasNonNull("commit") ? configured.path("commit").asText() : defaultCommit,
                configured.hasNonNull("pr") ? configured.path("pr").asText() : defaultPr);
        }
        if (settings != null && settings.path("includeCoAuthoredBy").isBoolean()
                && !settings.path("includeCoAuthoredBy").asBoolean()) {
            return new AttributionTexts("", "");
        }
        return new AttributionTexts(defaultCommit, defaultPr);
    }

    private static String applyAttribution(String template, AttributionTexts attribution) {
        String commit = attribution.commit();
        String pr = attribution.pr();
        String withCommit = template
            .replace("Create the commit with a message ending with:\n"
                    + "   Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>",
                commit.isEmpty() ? "Create the commit with a message."
                    : "Create the commit with a message ending with:\n   " + commit)
            .replace("""
                       Commit message here.

                       Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>\
                    """,
                commit.isEmpty() ? "   Commit message here."
                    : "   Commit message here.\n\n   " + commit);
        return withCommit.replace(
            """
                [Bulleted markdown checklist of TODOs for testing the pull request...]

                🤖 Generated with [Claude Code](https://claude.com/claude-code)""",
            pr.isEmpty()
                ? "[Bulleted markdown checklist of TODOs for testing the pull request...]"
                : "[Bulleted markdown checklist of TODOs for testing the pull request...]\n\n" + pr);
    }

    private record AttributionTexts(String commit, String pr) {}

    private static String attributionModelName(String model) {

        // rather than exposing a model generation in the Bash instructions.
        if (model != null && (Strings.CI.contains(model, "haiku-3")
                || Strings.CI.contains(model, "claude-3-haiku"))) {
            return "Claude";
        }
        String displayName = ModelNames.displayName(model);
        boolean knownPublic = model != null
            && !Strings.CS.equals("unknown", displayName)
            && !displayName.equals(model);
        if (!knownPublic) {
            return "Claude Opus 5";
        }
        return "Claude " + displayName.replace(" (1M context)", "");
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    @Override
    public Object call(JsonNode input, ToolExecutionContext context) {
        String command = input.has("command") ? input.get("command").asText("") : "";

        long defaultTimeoutMs = BashTimeouts.defaultTimeoutMs(envLookup);
        long maxTimeoutMs = BashTimeouts.maxTimeoutMs(envLookup);
        long timeoutMs = input.has("timeout")
            ? input.get("timeout").asLong(defaultTimeoutMs) : defaultTimeoutMs;
        if (timeoutMs <= 0) timeoutMs = defaultTimeoutMs;
        timeoutMs = Math.min(timeoutMs, maxTimeoutMs);
        boolean runInBackground = input.has("run_in_background") && input.get("run_in_background").asBoolean(false);
        // Sandbox decision. `dangerouslyDisableSandbox` is the model's per-call
        // opt-OUT of a sandbox; it only matters when a sandbox is configured.
        // When no sandbox is configured (the default) or the command is
        // excluded, commands run unsandboxed — matching pre-sandbox behavior.
        // When a sandbox IS wanted but the native backend is unavailable, the
        // decision is REJECT (per settings.sandbox.failIfUnavailable, default
        // true) so we surface the error instead of silently running unsandboxed.
        boolean dangerouslyDisableSandbox =
            input.has("dangerouslyDisableSandbox") && input.get("dangerouslyDisableSandbox").asBoolean(false);
        // context is nullable at the API boundary; downstream executors
// dereference workingDirectory/abortController/etc., so resolve a
        // minimal fallback (cwd = user.dir, NOOP progress sink) once here
        // instead of re-guarding at every call site.
        if (context == null) {
            context = ToolExecutionContext.of(new AbortController(), "unknown");
        }
        if (input.has("_simulatedSedEdit") && input.get("_simulatedSedEdit").isObject()) {
            return applySimulatedSedEdit(input.get("_simulatedSedEdit"), context);
        }
        SandboxConfig sandboxConfig = context.sandboxConfig();
        SandboxDecision decision = sandboxManager.decide(command, dangerouslyDisableSandbox, sandboxConfig);
        if (decision.isReject()) {
            return "Error: " + decision.rejectReason();
        }

        if (org.apache.commons.lang3.StringUtils.isBlank(command)) {
            return "Error: command is empty";
        }

        // F2: block a leading `sleep N` (N >= 2s) that would stall the assistant.
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

        try {
            if (runInBackground) {
                return executeBackgroundCommand(command, context, decision);
            }
            return executeCommand(command, timeoutMs, context, decision);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: command was interrupted";
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String applySimulatedSedEdit(JsonNode simulated,
                                                ToolExecutionContext context) {
        String rawPath = simulated.path("filePath").asText("");
        String newContent = simulated.path("newContent").asText("");
        Path path = Path.of(context.workingDirectory()).resolve(rawPath)
            .toAbsolutePath().normalize();
        final FileTextUtils.TextFile original;
        try {
            original = FileTextUtils.readWithMetadata(path);
        } catch (NoSuchFileException _) {
            return "sed: " + rawPath + ": No such file or directory\nExit code 1";
        } catch (IOException error) {
            return "Error: " + error.getMessage();
        }
        try {
            if (context.fileHistoryManager() != null) {
                context.fileHistoryManager().trackEdit(
                    path.toString(), context.currentUserMessageId());
            }
            String toWrite = FileTextUtils.restoreLineEndings(newContent, original.lineEnding());
            Files.writeString(path, toWrite, original.charset(),
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            context.fileStateCache().set(path.toString(),
                new FileStateCache.FileState(
                    newContent, FileUtils.modificationTimeMillis(path),
                    null, null, false));
            return "";
        } catch (IOException error) {
            return "Error: " + error.getMessage();
        }
    }


    public static boolean isSilentBashCommand(String command) {
        List<ShellQuoteParse.Token> tokens;
        try {
            tokens = ShellQuoteParse.parse(command);
        } catch (RuntimeException _) {
            return false;
        }
        if (tokens.isEmpty()) return false;

        List<String> parts = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        for (ShellQuoteParse.Token token : tokens) {
            if (token instanceof ShellQuoteParse.Op(String op1)
                    && Set.of(">", ">>", ">&", "||", "&&", "|", ";").contains(op1)) {
                if (!segment.isEmpty()) {
                    parts.add(segment.toString());
                    segment.setLength(0);
                }
                parts.add(op1);
            } else if (org.apache.commons.lang3.StringUtils.isNotBlank(token.asString())) {
                if (!segment.isEmpty()) segment.append(' ');
                segment.append(token.asString());
            }
        }
        if (!segment.isEmpty()) parts.add(segment.toString());
        if (parts.isEmpty()) return false;

        boolean hasNonFallbackCommand = false;
        String lastOperator = null;
        boolean skipNextAsRedirectTarget = false;
        for (String part : parts) {
            if (skipNextAsRedirectTarget) {
                skipNextAsRedirectTarget = false;
                continue;
            }
            if (Strings.CS.equals(part, ">") || Strings.CS.equals(part, ">>") || Strings.CS.equals(part, ">&")) {
                skipNextAsRedirectTarget = true;
                continue;
            }
            if (Strings.CS.equals(part, "||") || Strings.CS.equals(part, "&&") || Strings.CS.equals(part, "|") || Strings.CS.equals(part, ";")) {
                lastOperator = part;
                continue;
            }
            String baseCommand = part.trim().split("\\s+")[0];
            if (baseCommand.isEmpty()) continue;
            if (Strings.CS.equals("||", lastOperator) && SEMANTIC_NEUTRAL_COMMANDS.contains(baseCommand)) {
                continue;
            }
            hasNonFallbackCommand = true;
            if (!SILENT_COMMANDS.contains(baseCommand)) return false;
        }
        return hasNonFallbackCommand;
    }

    private Object executeCommand(String command, long timeoutMs,
                                   ToolExecutionContext context,
                                   SandboxDecision decision) throws IOException, InterruptedException {
        SudoCommandAdapter.Result sudoResult =
            SudoCommandAdapter.prepare(command, sudoPasswordInteraction);
        if (sudoResult instanceof SudoCommandAdapter.Result.Rejected(var message)) {
            return message;
        }
        SudoCommandAdapter.Result.Prepared sudoCommand =
            sudoResult instanceof SudoCommandAdapter.Result.Prepared prepared ? prepared : null;

        // Task 56.8: Track git operations
        trackGitOperation(command, context.workingDirectory());

        SandboxConfig sandboxConfig = context.sandboxConfig();
        ShellWorkingDirectoryTracker cwdTracker =
            ShellWorkingDirectoryTracker.start(context, envLookup);
        String commandToExecute = sudoCommand == null ? command : sudoCommand.command();
        String executableCommand = cwdTracker.wrap(commandToExecute);
        Path bareGitCwd = Path.of(context.workingDirectory());
        List<String> argv = resolveCommandLine(executableCommand, bareGitCwd, decision, sandboxConfig);
        ProcessBuilder pb = new ProcessBuilder(argv);
        SubprocessEnvironment.applyTo(pb.environment());
        if (Platform.IS_WINDOWS) {
            pb.environment().put("SHELL", ExecutableFinder.bashExecutable());
        }
        pb.directory(bareGitCwd.toFile());
        // Domain-allowlist proxy env (sandbox.network.allowedDomains): routes
        // the sandboxed command's network through the parent proxy that enforces
        // the allowlist. Only meaningful when actually sandboxed (the proxy is
        // part of the sandbox runtime); with no sandbox there is nothing to filter.
        if (decision.isSandboxed()) {
            pb.environment().putAll(sandboxManager.sandboxEnvironment(sandboxConfig));
        }
        pb.redirectErrorStream(false);

        // Snapshot bare-repo files present now so we can scrub any planted during
        // the (sandboxed) command — prevents a later unsandboxed git from being
        // fooled into treating cwd as a bare repo (anthropics/claude-code#29316).
        Set<Path> bareGitBefore = decision.isSandboxed()
            ? SandboxManager.bareGitFilesSnapshot(bareGitCwd) : Set.of();

        Process process;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            if (sudoCommand != null) sudoCommand.close();
            cwdTracker.discard();
            throw e;
        }
        try {
            if (sudoCommand != null) {
                sudoCommand.writePasswordTo(process.getOutputStream());
            }
        } catch (IOException e) {
            ProcessTreeTerminator.terminate(process, Duration.ZERO);
            cwdTracker.discard();
            throw e;
        } finally {
            if (sudoCommand != null) sudoCommand.close();
            // Non-interactive commands still see EOF immediately; sudo receives
            // exactly one password line before the same close.
            try { process.getOutputStream().close(); } catch (IOException _) {}
        }

        // Register abort handler
        context.abortController().onAbort(() -> {
            if (process.isAlive()) {
                ProcessTreeTerminator.terminate(process, Duration.ofMillis(250));
            }
        });

        // Read stdout and stderr in parallel using virtual threads
        // Bounded accumulators synchronize progress-tail reads with process drains.
        StringUtils.EndTruncatingAccumulator stdout =
            new StringUtils.EndTruncatingAccumulator(MAX_OUTPUT_SIZE);
        StringUtils.EndTruncatingAccumulator stderr =
            new StringUtils.EndTruncatingAccumulator(MAX_OUTPUT_SIZE);
        final AtomicLong totalBytes = new AtomicLong(0);
        final AtomicInteger lineCount = new AtomicInteger(0);
        Object outputLock = new Object();
        AtomicReference<OutputStream> detachedOutput = new AtomicReference<>();
        TaskRegistry foregroundRegistry = BackgroundTaskGate.disabled()
            ? null : TaskRegistry.global();
        AtomicReference<ForegroundShellTask> foregroundHandle = new AtomicReference<>();

        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    totalBytes.addAndGet(line.length() + 1);
                    lineCount.incrementAndGet();
                    // Task 56.4: Large output persistence
                    synchronized (outputLock) {
                        String chunk = line + '\n';
                        stdout.append(chunk);
                        writeDetachedOutput(detachedOutput.get(), chunk);
                    }
                }
            } catch (IOException _) {
                // Process may have been destroyed
            }
        });

        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (outputLock) {
                        String chunk = line + '\n';
                        stderr.append(chunk);
                        writeDetachedOutput(detachedOutput.get(), chunk);
                    }
                }
            } catch (IOException _) {
                // Process may have been destroyed
            }
        });


        final long progressStart = System.currentTimeMillis();
        final boolean[] progressDone = {false};
        ScheduledExecutorService progressScheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        ScheduledFuture<?> progressFuture = progressScheduler.scheduleAtFixedRate(() -> {
            if (progressDone[0] || !process.isAlive()) return;
            long elapsedSec = (System.currentTimeMillis() - progressStart) / 1000;

            // lines -> fullOutput, both taken from the trailing PROGRESS_TAIL_CHARS
            // window of the live buffer.
            String window = stdout.tail(StringUtils.PROGRESS_TAIL_CHARS);
            var t = StringUtils.progressTail(window);
            String summary = String.format(
                "bash · %d lines · %d bytes · %ds",
                lineCount.get(), totalBytes.get(), elapsedSec);


            context.reportProgress(ToolExecutionContext.ProgressUpdate.of(
                0.0, summary, "bash_progress", null, null,
                t.last5(), t.last100(), lineCount.get(), totalBytes.get(),
                (System.currentTimeMillis() - progressStart) / 1000.0, timeoutMs, false));
        }, PROGRESS_THRESHOLD_MS, PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS);

        long waitStarted = System.currentTimeMillis();
        boolean completed = false;
        while (!completed) {
            long elapsed = System.currentTimeMillis() - waitStarted;
            long remaining = Math.max(0L, timeoutMs - elapsed);
            if (remaining == 0L) break;
            completed = process.waitFor(Math.min(remaining, 50L), TimeUnit.MILLISECONDS);
            if (!completed && process.isAlive() && foregroundRegistry != null
                    && context.progressSink() != ToolExecutionContext.ProgressSink.NOOP
                    && foregroundHandle.get() == null
                    && System.currentTimeMillis() - progressStart >= PROGRESS_THRESHOLD_MS) {
                TaskState foregroundTask = foregroundRegistry.store().create(
                    TaskType.LOCAL_BASH, command, context.agentId());
                if (context.toolUseId() != null) {
                    foregroundTask = foregroundRegistry.store().updateToolUseId(
                        foregroundTask.id(), context.toolUseId());
                }
                foregroundRegistry.store().updateStatus(foregroundTask.id(),
                    TaskStatus.RUNNING);
                Path outputPath = TaskOutputPaths.outputPath(foregroundTask.id(), context);
                ForegroundShellTask registered = new ForegroundShellTask(
                    foregroundTask, foregroundRegistry.store(), outputPath, () -> {
                        synchronized (outputLock) {
                            if (detachedOutput.get() != null) return;
                            try {
                                Files.createDirectories(outputPath.getParent());
                                OutputStream stream = Files.newOutputStream(outputPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING);
                                stream.write(stdout.toString().getBytes(StandardCharsets.UTF_8));
                                stream.write(stderr.toString().getBytes(StandardCharsets.UTF_8));
                                stream.flush();
                                detachedOutput.set(stream);
                            } catch (IOException e) {
                                throw new IllegalStateException(
                                    "Failed to open background output: " + e.getMessage(), e);
                            }
                        }
                    });
                registered.setProcess(process);
                foregroundHandle.set(registered);
                foregroundRegistry.registerShellForeground(registered);
                context.reportProgress(0.0, "Press Ctrl+B to run in background");
            }
            ForegroundShellTask liveForegroundHandle = foregroundHandle.get();
            if (!completed && liveForegroundHandle != null
                    && liveForegroundHandle.backgroundSignal().isDone()) {
                long detachedRemaining = Math.max(0L,
                    timeoutMs - (System.currentTimeMillis() - waitStarted));
                Thread.ofVirtual().name("fg-shell-detached-" + liveForegroundHandle.getTaskId())
                    .start(() -> finishDetachedShell(process, stdoutThread, stderrThread,
                        progressDone, progressFuture, progressScheduler, detachedOutput,
                        decision, bareGitCwd, bareGitBefore, cwdTracker,
                        liveForegroundHandle, detachedRemaining));
                context.reportProgress(ToolExecutionContext.ProgressUpdate.builder()
                    .complete(true)
                    .build());
                return "Command was manually backgrounded by user with ID: "
                    + liveForegroundHandle.getTaskId()
                    + ". Output is being written to: "
                    + liveForegroundHandle.getOutputPath();
            }
        }
        progressDone[0] = true;
        progressFuture.cancel(true);
        progressScheduler.shutdownNow();

        if (!completed) {
            ProcessTreeTerminator.terminate(process, Duration.ofMillis(500));
            process.waitFor(5, TimeUnit.SECONDS);
            stdoutThread.join(1000);
            stderrThread.join(1000);
            if (decision.isSandboxed()) {
                SandboxManager.scrubBareGitRepoFiles(bareGitCwd, bareGitBefore);
            }
            cwdTracker.discard();
            ForegroundShellTask liveForegroundHandle = foregroundHandle.get();
            if (foregroundRegistry != null && liveForegroundHandle != null) {
                foregroundRegistry.unregisterForegroundShell(liveForegroundHandle.getTaskId());
            }
            return "Error: command timed out after " + (timeoutMs / 1000) + " seconds\n"
                    + stdout + stderr;
        }

        stdoutThread.join(5000);
        stderrThread.join(5000);

        int exitCode = process.exitValue();
        ForegroundShellTask liveForegroundHandle = foregroundHandle.get();
        if (foregroundRegistry != null && liveForegroundHandle != null
                && !foregroundRegistry.unregisterForegroundShell(
                    liveForegroundHandle.getTaskId())) {
            closeDetachedOutput(detachedOutput.get());
            liveForegroundHandle.complete(exitCode);
            if (decision.isSandboxed()) {
                SandboxManager.scrubBareGitRepoFiles(bareGitCwd, bareGitBefore);
            }
            cwdTracker.finish();
            return "Command was manually backgrounded by user with ID: "
                + liveForegroundHandle.getTaskId()
                + ". Output is being written to: "
                + liveForegroundHandle.getOutputPath();
        }
        String rawOutput = stdout.toString();
        String cwdResetWarning = cwdTracker.finish();


// URI, return a real image block so the model sees the image — not a text note.
        ToolResult imageResult = tryImageResult(rawOutput);
        if (imageResult != null) {
            return imageResult;
        }


        // before the model sees the output (hints are a harness-only side channel
        // surfaced to the user as a plugin-install prompt, never appended to the
        // model's view). Hints may appear on stdout or in the merged stderr stream,
        // so both are scanned and stripped; accepted plugin hints are recorded in
        // the single-slot store for the UI to surface (once per session).
        HintExtraction outExtraction = ClaudeCodeHints.extractClaudeCodeHints(rawOutput, command);
        HintExtraction errExtraction =
            ClaudeCodeHints.extractClaudeCodeHints(stderr.toString(), command);
        for (ClaudeCodeHint hint : outExtraction.hints()) {
            ClaudeCodeHintStore.getInstance().recordPluginHint(hint);
        }
        for (ClaudeCodeHint hint : errExtraction.hints()) {
            ClaudeCodeHintStore.getInstance().recordPluginHint(hint);
        }

// BASH_MAX_OUTPUT_LENGTH truncation — applied to the stripped output so hint lines never
// reach the model.
        String strippedOutput = OutputLimits.stripEmptyLines(outExtraction.stripped());
        String strippedErrors = errExtraction.stripped();
        boolean inlineOutputTruncated = OutputLimits.wouldTruncate(strippedOutput, envLookup)
            || OutputLimits.wouldTruncate(strippedErrors, envLookup);
        String output = OutputLimits.formatOutput(strippedOutput, envLookup);
        String errors = OutputLimits.formatOutput(strippedErrors, envLookup);
        if (cwdResetWarning != null) {
            errors = org.apache.commons.lang3.StringUtils.isBlank(errors) ? cwdResetWarning : errors.stripTrailing() + "\n" + cwdResetWarning;
        }

        StringBuilder result = new StringBuilder();
        if (!output.isEmpty()) {
            result.append(output);
        }
        if (!errors.isEmpty()) {
            if (!result.isEmpty()) result.append('\n');
            result.append(errors);
        }

        // "no matches" result, find/diff/test/[ exit 1 as a non-error status,
        // and only their higher codes as failures. Keep the model-visible
        // output free of a misleading Exit code line for those statuses.
        CommandSemantics.Interpretation interpretation =
            CommandSemantics.bash(command, exitCode, output, errors);
        if (interpretation.isError()) {
            result.append("\nExit code: ").append(exitCode);
        } else {
            cacheShellReadFiles(command, context);
        }

        // Persist raw stdout and stderr in the original session-scoped tool-results
        // directory, not a fabricated project .claude/tool-results directory.
        if (inlineOutputTruncated || totalBytes.get() > MAX_INLINE_LINES * 200) {
            Path toolResultsDir = toolResultsDirectoryResolver.apply(
                context.workingDirectory(), context.sessionId());
            Files.createDirectories(toolResultsDir);
            Path outputFile = toolResultsDir.resolve(
                TaskIdGenerator.generate(TaskType.LOCAL_BASH) + ".txt");
            String rawErrors = stderr.toString();
            String separator = rawErrors.isEmpty() || rawOutput.isEmpty()
                || Strings.CS.endsWith(rawOutput, "\n") ? "" : "\n";
            String persistedOutput = rawOutput + separator + rawErrors;
            Files.writeString(outputFile, persistedOutput, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            result.append("\n\n[Output truncated: ").append(persistedOutput.length()).append(" chars. Full output saved to: ")
                  .append(outputFile).append("]");
        }

        if (decision.isSandboxed()) {
            SandboxManager.scrubBareGitRepoFiles(bareGitCwd, bareGitBefore);
        }
        return result.toString();
    }

    private static void finishDetachedShell(
            Process process, Thread stdoutThread, Thread stderrThread,
            boolean[] progressDone, ScheduledFuture<?> progressFuture,
            ScheduledExecutorService progressScheduler,
            AtomicReference<OutputStream> detachedOutput,
            SandboxDecision decision, Path bareGitCwd, Set<Path> bareGitBefore,
            ShellWorkingDirectoryTracker cwdTracker, ForegroundShellTask handle,
            long timeoutMs) {
        try {
            boolean completed = timeoutMs > 0
                && process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                ProcessTreeTerminator.terminate(process, Duration.ofMillis(500));
                process.waitFor(5, TimeUnit.SECONDS);
            }
            stdoutThread.join(5_000);
            stderrThread.join(5_000);
            progressDone[0] = true;
            progressFuture.cancel(true);
            progressScheduler.shutdownNow();
            closeDetachedOutput(detachedOutput.get());
            if (decision.isSandboxed()) {
                SandboxManager.scrubBareGitRepoFiles(bareGitCwd, bareGitBefore);
            }
            if (completed) {
                cwdTracker.finish();
                handle.complete(process.exitValue());
            } else {
                cwdTracker.discard();
                handle.fail("command timed out after " + (timeoutMs / 1000) + " seconds");
            }
        } catch (Exception e) {
            closeDetachedOutput(detachedOutput.get());
            cwdTracker.discard();
            handle.fail(e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private static void writeDetachedOutput(OutputStream output, String chunk) {
        if (output == null) return;
        try {
            output.write(chunk.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException _) {
            // Task status remains authoritative when the output file fails.
        }
    }

    private static void closeDetachedOutput(OutputStream output) {
        if (output == null) return;
        try {
            output.close();
        } catch (IOException _) {
            // Best-effort close after process completion.
        }
    }


    private static void cacheShellReadFiles(String command, ToolExecutionContext context) {
        Path cwd = Path.of(context.workingDirectory());
        for (Path path : BashPermissions.extractReadableFilePaths(command, cwd)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                long timestamp = FileUtils.modificationTimeMillis(path);
                context.fileStateCache().set(path.toString(),
                    new FileStateCache.FileState(
                        content, timestamp, null, null, false));
            } catch (IOException | RuntimeException _) {
                // The command already completed successfully; cache enrichment is best-effort.
            }
        }
    }

    /**
     * Resolve the argv to exec for a command, wrapping it in the native sandbox
     * when {@code decision} says sandboxed (otherwise a plain {@code bash -c}).
     * Pure (no process launch) so it is unit-testable without spawning processes.
     */
    List<String> resolveCommandLine(String command, Path cwd,
                                            SandboxDecision decision, SandboxConfig cfg) {
        if (decision.isSandboxed()) {
            return sandboxManager.wrap(command, cwd, cfg);
        }
        return List.of(ExecutableFinder.bashExecutable(), "-c", command);
    }

    /**
     * Task 56.2: Execute command in background.
     */
    private String executeBackgroundCommand(String command,
                                             ToolExecutionContext context,
                                             SandboxDecision decision) throws IOException {
        TaskRegistry registry = TaskRegistry.global();
// Stamp the task with the current agent id (null on the main thread) so its
// completion/stall notifications are routed to the owning agent instead of the coordinator.
        TaskState task = registry.store().create(TaskType.LOCAL_BASH, command, context.agentId());
        LocalShellTask handle = new LocalShellTask(task, command, registry.store(),
            TaskOutputPaths.outputPath(task.id(), context), sandboxManager, decision, context.sandboxConfig(),
            context.progressSink());

        try {
            handle.start(context.workingDirectory());
        } catch (IOException | RuntimeException e) {
            // The process never started: evict the just-created PENDING task
            // so it doesn't linger forever as an un-killable phantom row in
            // /tasks. The handle is registered only after a successful start
            // for the same reason.
            registry.store().remove(task.id());
            throw e;
        }
        registry.registerShell(handle);



        return "Command running in background with ID: " + task.id()
            + ". Output is being written to: " + handle.getOutputPath();
    }

    /**
     * Task 56.8: Track git operations and detect .git/index.lock errors.
     */
    private void trackGitOperation(String command, String workingDirectory) {
        if (!Strings.CS.startsWith(command, "git ")) return;

        // Check if this is a write operation
        for (String writeCmd : GIT_WRITE_COMMANDS) {
            if (Strings.CS.startsWith(command, writeCmd)) {
                // Could notify LSP or other watchers here
                break;
            }
        }

        // Check for .git/index.lock
        Path lockFile = Path.of(workingDirectory, ".git", "index.lock");
        if (Files.exists(lockFile)) {
            // Another git operation is in progress
            log.warn(".git/index.lock exists; another git operation may be in progress");
        }
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
            // Decode/resize failed — send text instead of an image.
            return null;
        }
    }

/* Live-progress tail helper shared with {@code LocalShellTask} lives in StringUtils.progressTail(String). */

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        String command = input.has("command") ? input.get("command").asText("") : "";
        return BashPermissions.check(command, permCtx);
    }


    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input != null ? input.path("command").asText("") : "";
    }


    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        if (input == null || !input.has("command")) return false;
        if (input.has("run_in_background") && input.get("run_in_background").asBoolean(false)) {
            return false;
        }
        String command = input.get("command").asText("");
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) return false;

        return isReadOnlyCommand(command);
    }

    /**
     * Compound-aware read-only check built on the shell-aware tokenizer (replacing the old naive {@code
     * command.split(...)}): every segment between control operators ({@code; && || |}) must be a
     * read-only command.
     */
    private boolean isReadOnlyCommand(String command) {
        if (hasOutputRedirection(command)) return false;
        List<String> segments = segmentLeadingCommands(command);
        for (String seg : segments) {
            if (!isSingleReadCommand(seg)) return false;
        }
        return true;
    }

    /**
     * Determines if a command is a search or read-only command.
     */
    public static boolean isSearchOrReadCommand(String command) {
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) return false;
        List<String> segments = segmentLeadingCommands(command);
        for (String seg : segments) {
            if (!isSingleReadCommand(seg)) return false;
        }
        return true;
    }

    /** Released task-progress classification used by the teammate task board. */
    public static SearchReadClassification classifySearchOrReadCommand(String command) {
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) {
            return SearchReadClassification.NONE;
        }
        boolean search = false;
        boolean read = false;
        boolean list = false;
        boolean classifiedAny = false;
        for (String segment : segmentLeadingCommands(command)) {
            String[] words = segment.trim().split("\\s+");
            if (words.length == 0 || words[0].isEmpty()) continue;
            String executable = words[0];
            if (SEMANTIC_NEUTRAL_COMMANDS.contains(executable)) continue;
            classifiedAny = true;
            boolean segmentSearch = TASK_ACTIVITY_SEARCH_COMMANDS.contains(executable);
            boolean segmentRead = TASK_ACTIVITY_READ_COMMANDS.contains(executable);
            boolean segmentList = TASK_ACTIVITY_LIST_COMMANDS.contains(executable);
            if (!segmentSearch && !segmentRead && !segmentList) {
                return SearchReadClassification.NONE;
            }
            search |= segmentSearch;
            read |= segmentRead;
            list |= segmentList;
        }
        return classifiedAny
            ? new SearchReadClassification(search, read, list)
            : SearchReadClassification.NONE;
    }

    public record SearchReadClassification(boolean isSearch, boolean isRead, boolean isList) {
        private static final SearchReadClassification NONE =
            new SearchReadClassification(false, false, false);
    }

    /** True when the command contains an output redirection ({@code >}/{@code >>}/{@code >&}). */
    private static boolean hasOutputRedirection(String command) {
        for (ShellQuoteParse.Token t : ShellQuoteParse.parse(command)) {
            if (t instanceof ShellQuoteParse.Op(String o)) {
              if (Strings.CS.equals(o, ">") || Strings.CS.equals(o, ">>") || Strings.CS.equals(o, ">&")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Splits a command into per-segment leading commands using the shell-aware {@link ShellQuoteParse}
     * tokenizer (replacing the previous naive {@code split}), so quoted operators/pipes are not
     * mistaken for separators.
     */
    private static List<String> segmentLeadingCommands(String command) {
        List<String> segments = new ArrayList<>();
        List<String> curWords = new ArrayList<>();
        for (ShellQuoteParse.Token t : ShellQuoteParse.parse(command)) {
            if (t instanceof ShellQuoteParse.Op(String o)) {
              if (Strings.CS.equals(o, "|") || Strings.CS.equals(o, ";") || Strings.CS.equals(o, "&&") || Strings.CS.equals(o, "||")) {
                    if (!curWords.isEmpty()) {
                        segments.add(joinLeading(curWords));
                        curWords.clear();
                    }
                }
                // '>', '>>', '>&', '<', '<(', '<<<' → redirections: stripped (ignored)
            } else if (t instanceof ShellQuoteParse.Word(String value)) {
                if (curWords.size() < 2) curWords.add(value);
            } else if (t instanceof ShellQuoteParse.Glob(String pattern)) {
                if (curWords.size() < 2) curWords.add(pattern);
            }
            // Comment → rest of line already consumed by the tokenizer
        }
        if (!curWords.isEmpty()) {
            segments.add(joinLeading(curWords));
        }
        return segments;
    }

    private static String joinLeading(List<String> words) {
        if (words.isEmpty()) return "";
        return words.size() >= 2 ? words.getFirst() + " " + words.get(1) : words.getFirst();
    }

    private static boolean isSingleReadCommand(String command) {
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) return false;
        String cmd = command.trim();

        // Extract the base command (first word)
        String baseCmd = cmd.split("\\s+")[0];

        // Check direct match
        if (SEARCH_READ_COMMANDS.contains(baseCmd)) return true;

        // Check two-word commands (e.g., "git log")
        String[] words = cmd.split("\\s+");
        if (words.length >= 2) {
            String twoWord = words[0] + " " + words[1];
            return SEARCH_READ_COMMANDS.contains(twoWord);
        }

        return false;
    }

    /**
     * Checks if a command appears incomplete (trailing pipe, &&, etc.).
     */
    public static boolean isIncompleteCommand(String command) {
        if (org.apache.commons.lang3.StringUtils.isBlank(command)) return false;
        return INCOMPLETE_COMMAND_PATTERN.matcher(command.trim()).find();
    }

    /**
     * Detects a leading {@code sleep N} (integer seconds, N &ge; 2) that would block the assistant and
     * should run in the background.
     */
    private static String detectBlockedSleepPattern(String command) {
        StringBuilder first = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        boolean afterSep = false;
        for (ShellQuoteParse.Token t : ShellQuoteParse.parse(command)) {
            if (t instanceof ShellQuoteParse.Op(String o)) {
              if (Strings.CS.equals(o, "|") || Strings.CS.equals(o, ";") || Strings.CS.equals(o, "&&") || Strings.CS.equals(o, "||")) {
                    if (afterSep) {
                        // already collecting the trailing rest; record the
                        // separator so the rest reconstruction reads naturally.
                        if (!rest.isEmpty()) rest.append(' ');
                        rest.append(o);
                    }
                    afterSep = true;
                    continue;
                }
                // other ops (redirection etc.) → ignore within the leading segment
            } else if (t instanceof ShellQuoteParse.Word(String value)) {
                if (afterSep) {
                    if (!rest.isEmpty()) rest.append(' ');
                    rest.append(value);
                } else {
                    if (!first.isEmpty()) first.append(' ');
                    first.append(value);
                }
            } else if (t instanceof ShellQuoteParse.Glob(String pattern)) {
                if (afterSep) {
                    if (!rest.isEmpty()) rest.append(' ');
                    rest.append(pattern);
                } else {
                    if (!first.isEmpty()) first.append(' ');
                    first.append(pattern);
                }
            }
        }
        String firstSeg = first.toString().trim();
        Matcher m = SLEEP_PATTERN.matcher(firstSeg);
        if (!m.find()) return null;
        int secs = Integer.parseInt(m.group(1));
        if (secs < 2) return null;
        String restText = rest.toString().trim();
        return restText.isEmpty() ? "standalone sleep " + secs : "sleep " + secs + " followed by: " + restText;
    }

    private static JsonNode buildSchema(long maxTimeoutMs) {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");


        ObjectNode commandProp = properties.putObject("command");
        commandProp.put("description", "The command to execute");
        commandProp.put("type", "string");

        ObjectNode timeoutProp = properties.putObject("timeout");
        timeoutProp.put("description",
            "Optional timeout in milliseconds (max " + maxTimeoutMs + ")");
        timeoutProp.put("type", "number");

        ObjectNode descProp = properties.putObject("description");
        descProp.put("description",
            """
                Clear, concise description of what this command does in active voice. \
                Never use words like "complex" or "risk" in the description - just \
                describe what it does.

                For simple commands (git, npm, standard CLI \
                tools), keep it brief (5-10 words):
                - ls → "List files in current \
                directory"
                - git status → "Show working tree status"
                - npm install \
                → "Install package dependencies"

                For commands that are harder to \
                parse at a glance (piped commands, obscure flags, etc.), add enough \
                context to clarify what it does:
                - find . -name "*.tmp" -exec rm {} \
                \\; → "Find and delete all .tmp files recursively"
                - git reset \
                --hard origin/main → "Discard all local changes and match remote \
                main"
                - curl -s url | jq '.data[]' → "Fetch JSON from URL and \
                extract data array elements\"""");
        descProp.put("type", "string");

        if (!BackgroundTaskGate.disabled()) {
            ObjectNode bgProp = properties.putObject("run_in_background");
            bgProp.put("description", "Set to true to run this command in the background.");
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
