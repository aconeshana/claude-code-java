package com.claudecode.services.hooks;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.process.PlatformShellCommand;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code type:"command"} hooks as shell subprocesses: synchronous
 * execution with the exit-code / JSON-stdout contract, the output-driven
 * {@code {"async":true}} handshake, config-declared async hooks, background
 * completion with the {@code asyncTimeout} kill floor, and {@code asyncRewake}
 * model wake-ups.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — {@code executeHookCommand}: subprocess
 *       launch, stdin/env {@code HOOK_INPUT}, exit-code 2 blocking semantics,
 *       JSON stdout precedence, timeout kill.</li>
 *   <li>{@code src/utils/hooks.ts} — {@code runAsyncHook} / async handshake
 *       detection and {@code asyncTimeout} enforcement.</li>
 *   <li>{@code src/utils/hooks/AsyncHookRegistry.ts} — registration of
 *       still-running hooks whose results are re-injected on a later turn
 *       (state lives in {@link AsyncHookRegistry}).</li>
 *   <li>{@code src/utils/hooks/registerSkillHooks.ts} — {@code CLAUDE_PLUGIN_ROOT}
 *       substitution and environment for skill-provided hooks.</li>
 * </ul>
 */
final class BashHookExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(BashHookExecutor.class);

    /** Default timeout for normal tool hooks. */
    static final int TOOL_HOOK_TIMEOUT_SECONDS = 600;  // 10 minutes

    /**
     * Per-dispatch capture that lets the compact outcome aggregator distinguish
     * an output-driven async handoff from an ordinary no-output Skip without
     * changing the public HookResult contract used by direct callers.
     */
    private static final ScopedValue<OutputDrivenAsyncCapture> OUTPUT_DRIVEN_ASYNC_CAPTURE =
        ScopedValue.newInstance();

    static final class OutputDrivenAsyncCapture {
        private boolean backgrounded;
        private String initialOutput;

        void background(String output) {
            backgrounded = true;
            initialOutput = output;
        }

        boolean backgrounded() {
            return backgrounded;
        }

        String initialOutput() {
            return initialOutput;
        }
    }

    /** A still-running bash hook subprocess plus its drain threads. */
    private record RunningBashHook(
        Process process,
        StringBuilder stderr,
        Thread stderrDrain,
        BufferedReader stdoutReader
    ) {}

    private final HookSessionContext context;
    private final HookRegistry registry;
    private final HookEffects effects;
    private final HookOutputParser parser;
    /**
     * Per-engine registry of in-flight output-driven and config-async hooks.
     * Completed responses are polled by {@link #checkForAsyncHookResponses}
     * and re-injected as attachments on a later turn.
     */
    private final AsyncHookRegistry asyncHooks = new AsyncHookRegistry();
    /**
     * When true, async hooks (both config-declared and output-driven) are NOT backgrounded — they
     * run synchronously so the caller blocks until completion.
     */
    private volatile boolean forceSync;

    BashHookExecutor(HookSessionContext context, HookRegistry registry, HookEffects effects,
                     HookOutputParser parser) {
        this.context = context;
        this.registry = registry;
        this.effects = effects;
        this.parser = parser;
    }

    void setForceSyncExecution(boolean value) {
        this.forceSync = value;
    }

    boolean forceSync() {
        return forceSync;
    }

    /** Runs {@code body} with a fresh capture visible to {@link #execute}. */
    static HookResult capturing(OutputDrivenAsyncCapture capture, Supplier<HookResult> body) {
        return ScopedValue.where(OUTPUT_DRIVEN_ASYNC_CAPTURE, capture).call(body::get);
    }

    // ---- async registry passthrough ----

    List<AsyncHookResponse> checkForAsyncHookResponses() {
        return asyncHooks.checkForAsyncHookResponses();
    }

    void removeDeliveredAsyncHooks(List<String> processIds) {
        asyncHooks.removeDeliveredAsyncHooks(processIds);
    }

    void finalizePendingAsyncHooks() {
        asyncHooks.finalizePendingAsyncHooks();
    }

    // ---- execution ----

    /**
     * Fire-and-forget launch of a config-declared {@code async} / {@code asyncRewake} hook on a
     * background virtual thread. The caller records a backgrounded Skip.
     */
    void launchDetached(BashCommandHook bash, HookInput input) {
        Thread.ofVirtual().start(() -> {
            try {
                RunningBashHook h = startProcess(bash, input);
                long asyncTimeoutMs = bash.timeoutSeconds().orElse(TOOL_HOOK_TIMEOUT_SECONDS) * 1000L;
                String pid = registerPending(bash, input, asyncTimeoutMs, h);
                completeInBackground(h, bash, asyncTimeoutMs, input, pid, true);
            } catch (IOException e) {
                LOG.debug("Config-async hook launch failed: {}", e.getMessage());
            }
        });
    }

    HookResult execute(BashCommandHook cmd, HookInput input, long defaultTimeoutMillis) {
        long timeoutMillis = cmd.timeoutSeconds()
            .map(seconds -> seconds * 1000L)
            .orElse(defaultTimeoutMillis);

        try {
            // Launch the subprocess. Throws IOException on broken pipe → the
            // caller returns Skip, matching the synchronous failure path. The
            // returned handle keeps the live process + drain threads so async
            // paths can hand it to a background thread.
            RunningBashHook h = startProcess(cmd, input);

            // Read stdout manually (no try-with-resources) so that, if the hook
            // self-declares output-driven async via its first stdout line
            // ({"async":true,...}), we can hand the still-open stream + process
            // to a background thread without the reader auto-closing
            // process.getInputStream(). See completeInBackground.
            StringBuilder stdout = new StringBuilder();
            boolean handedOff = false;
            try {
                String firstLine = h.stdoutReader.readLine();
                if (firstLine != null && isAsyncHandshake(firstLine)) {
                    if (!forceSync) {
                        long asyncTimeoutMs = resolveAsyncTimeout(firstLine, timeoutMillis);
                        String pid = registerPending(cmd, input, asyncTimeoutMs, h);
                        completeInBackground(h, cmd, asyncTimeoutMs, input, pid, true);
                        OutputDrivenAsyncCapture capture = OUTPUT_DRIVEN_ASYNC_CAPTURE.isBound()
                            ? OUTPUT_DRIVEN_ASYNC_CAPTURE.get() : null;
                        if (capture != null) capture.background(firstLine);
                        handedOff = true;
                        return HookResult.skip();
                    }
                    // forceSync: the async handshake is a control line, not hook
                    // output — drop it so the real result on later lines parses
                    // normally (the handshake is consumed, never re-injected).
                } else if (firstLine != null) {
                    stdout.append(firstLine).append('\n');
                }
                String line;
                while ((line = h.stdoutReader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            } finally {
                if (!handedOff) {
                    try {
                        h.stdoutReader.close();
                    } catch (IOException _) {
                        /* best-effort */
                    }
                }
            }

            boolean completed = h.process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                h.process.destroyForcibly();
                return HookResult.skip();
            }
            h.stderrDrain.join(1000);

            int exitCode = h.process.exitValue();
            String output = stdout.toString().trim();
            String rawErr = h.stderr.toString();
            String err = rawErr.trim();

            if (cmd.asyncRewake() && exitCode == 2) {
                effects.rewakeModel(HookOutcomes.hookEventName(input), err.isEmpty() ? output : err);
                return new HookResult.Message("Hook requested model rewake");
            }

            // JSON stdout takes precedence over the exit code.
            if (Strings.CS.startsWith(output, "{")) {
                return parser.parse(output, input.event());
            }

            // Exit 2 — blocking feedback built from stderr. HookMatcher/BashCommandHook
            // don't model a hook name, so the command string stands in for it.
            if (exitCode == 2) {
                return new HookResult.Block(
                    "[" + cmd.command() + "]: "
                        + (rawErr.isEmpty() ? "No stderr output" : rawErr));
            }

            // Other non-zero — non-blocking error, ignore.
            if (exitCode != 0) {
                return HookResult.skip();
            }

            return output.isEmpty() ? HookResult.allow() : new HookResult.Allow(output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.debug("Bash hook execution error: {}", e.getMessage());
            return HookResult.skip();
        }
    }

    private static boolean isAsyncHandshake(String firstLineJson) {
        try {
            JsonNode n = JsonUtils.getMapper().readTree(firstLineJson);
            return n.has("async") && n.get("async").asBoolean(false);
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Resolves the timeout for an output-driven async hook. An explicit
     * {@code asyncTimeout} in the handshake overrides the command's effective
     * execution timeout; otherwise the hook retains the same configured/default
     * timeout it had before being backgrounded.
     */
    @Explanation("Enforces the asyncTimeout supplied by an asynchronous hook handshake")
    private static long resolveAsyncTimeout(String firstLineJson, long commandTimeoutMillis) {
        long fromHandshake = -1;
        try {
            JsonNode n = JsonUtils.getMapper().readTree(firstLineJson);
            if (n.has("asyncTimeout")) {
                fromHandshake = n.get("asyncTimeout").asLong(-1);
            }
        } catch (Exception _) {
            /* fall through to defaults */
        }
        if (fromHandshake > 0) {
            return fromHandshake;
        }
        return commandTimeoutMillis;
    }

    /**
     * Completes a backgrounded async hook: drains remaining stdout on a side thread, enforces the
     * {@code asyncTimeoutMs} floor (force-kill on overrun), records the result in the registry when
     * {@code registerIntoRegistry} is true, and (for {@code asyncRewake} hooks) enqueues a
     * task-notification on exit 2.
     */
    private void completeInBackground(RunningBashHook h, BashCommandHook cmd,
            long asyncTimeoutMs, HookInput input, String processId, boolean registerIntoRegistry) {
        Thread.ofVirtual().start(() -> {
            try {
                // Drain remaining stdout on a side thread so we never block the
                // timeout logic on a still-running process — blocking on a full
                // readLine() here would let the hook run to completion and defeat
                // the asyncTimeout kill below.
                StringBuilder rest = new StringBuilder();
                Thread stdoutDrain = Thread.ofVirtual().start(() -> {
                    try {
                        String line;
                        while ((line = h.stdoutReader.readLine()) != null) {
                            rest.append(line).append('\n');
                        }
                    } catch (IOException _) {
                        /* best-effort */
                    }
                });
                boolean done;
                try {
                    // Enforce the timeout FIRST — a hung hook must be killed at
                    // asyncTimeoutMs, not after joining the drain threads. Joining
                    // stderr first would let a 1s-sleep hook finish before the
                    // 200ms timeout is ever checked.
                    done = h.process.waitFor(asyncTimeoutMs, TimeUnit.MILLISECONDS);
                    if (!done) {
                        h.process.destroyForcibly();   // asyncTimeout floor: kill a hung hook
                    }
                    h.stderrDrain.join(1000);
                    stdoutDrain.join(2000);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    h.process.destroyForcibly();
                    try {
                        h.stderrDrain.join(1000);
                        stdoutDrain.join(1000);
                    } catch (InterruptedException _) {
                        /* best-effort */
                    }
                    done = false;
                }
                int exitCode = h.process.exitValue();
                String stdout = rest.toString();
                String stderr = h.stderr.toString();
                if (registerIntoRegistry) {
                    asyncHooks.complete(processId, stdout, stderr, exitCode,
                        done ? AsyncHookRegistry.AsyncStatus.COMPLETED
                            : AsyncHookRegistry.AsyncStatus.KILLED);
                }
                if (cmd.asyncRewake() && exitCode == 2) {
                    effects.rewakeModel(HookOutcomes.hookEventName(input),
                        stderr.isEmpty() ? stdout : stderr);
                }
                // Plain async (no rewake): the process finishes in the background and its result
                // does not re-wake the model; it is recorded in the registry for later re-injection
                // as an attachment.
                LOG.debug("Async hook completed (exit {}): {}", exitCode, cmd.command());
            } catch (Exception e) {
                LOG.debug("Async hook background completion failed: {}", e.getMessage());
            } finally {
                try {
                    h.stdoutReader.close();
                } catch (IOException _) {
                    /* best-effort */
                }
                if (h.process.isAlive()) {
                    h.process.destroyForcibly();   // double safety net against orphan processes
                }
            }
        });
    }

    /**
     * Launches a BashCommandHook subprocess and returns a handle with the still-running process plus
     * its stderr/stdout drains.
     */
    private RunningBashHook startProcess(BashCommandHook cmd, HookInput input) throws IOException {
        Path skillRoot = registry.skillRoot(cmd);
        String command = cmd.command();
        if (skillRoot != null) {
            command = command.replace("${CLAUDE_PLUGIN_ROOT}", skillRoot.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(
            PlatformShellCommand.resolve(cmd.shell().orElse(null), command));
        SubprocessEnvironment.applyTo(pb.environment());

        // The live cwd may momentarily point at a deleted path. Only set the
        // subprocess directory when it still exists.
        File hookCwd = Path.of(context.cwd()).toFile();
        if (hookCwd.isDirectory()) {
            pb.directory(hookCwd);
        } else {
            LOG.debug("Hook cwd {} does not exist; inheriting JVM cwd", hookCwd);
        }
        pb.environment().put("HOOK_INPUT", input.toJson());
        if (skillRoot != null) {
            pb.environment().put("CLAUDE_PLUGIN_ROOT", skillRoot.toString());
        }
        pb.redirectErrorStream(false);

        Process process = pb.start();
        try {
            process.getOutputStream().write((input.toJson() + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
        } catch (IOException stdinErr) {
            LOG.debug("Hook stdin write failed (process likely exited early): {}", stdinErr.getMessage());
            try {
                process.getOutputStream().close();
            } catch (IOException _) {
                // The child already closed the pipe.
            }
        }

        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append('\n');
                }
            } catch (IOException _) { /* best-effort capture */ }
        });

        BufferedReader stdoutReader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        return new RunningBashHook(process, stderr, stderrDrain, stdoutReader);
    }

    /**
     * Registers a still-running async hook (output-driven or config-async) so
     * its completed result can later be polled and re-injected as an attachment.
     */
    private String registerPending(BashCommandHook cmd, HookInput input,
                                   long asyncTimeoutMs, RunningBashHook h) {
        String hookEvent = HookOutcomes.hookEventName(input);
        return asyncHooks.register(
            hookEvent, cmd.command(), hookEvent, input.toolName().orElse(null), null,
            asyncTimeoutMs, h.process);
    }
}
