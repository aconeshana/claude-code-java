package com.claudecode.services.shutdown;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.services.cost.CostStatePersistence;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.tasks.TeamSessionCleanup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the shutdown sequence when the REPL is about to exit — whether from {@code /exit},
 * {@code /logout}, {@code Ctrl+C}, {@code Ctrl+D}, a JVM {@code SIGTERM} shutdown hook, or a
 * worktree flow cancel.
 */
public final class GracefulShutdown {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    /** Single-shot guard — subsequent shutdown calls are no-ops. */
    private static final AtomicBoolean IN_PROGRESS = new AtomicBoolean(false);

/**
     * Failsafe headroom on top of the SessionEnd hook budget.
     */
    private static final long FAILSAFE_HEADROOM_MS = 3_500;
    private static final long FAILSAFE_MIN_MS      = 5_000;

    private GracefulShutdown() {}

/**
     * Whether a shutdown sequence has already started.
     */
    public static boolean isShuttingDown() {
        return IN_PROGRESS.get();
    }

    /** Test-only reset — clears the single-shot flag. */
    static void resetForTesting() {
        IN_PROGRESS.set(false);
    }

    /**
     * Run the orderly shutdown steps then hand back to the caller. Does NOT
     * call {@code System.exit} — the caller decides how the process winds
     * down (usually by stopping the Lanterna window and letting {@code main}
     * return).
     *
     * @param req  {@link Request} carrying the reason, current sessionId and
     *             plumbing needed to fire hooks / print the resume hint.
     */
    public static void run(Request req) {
        if (!IN_PROGRESS.compareAndSet(false, true)) {
            log.debug("Shutdown already in progress; ignoring {}", req.reason);
            return;
        }
        try (FailsafeTimer ignored = armFailsafe(req)) {
            // Persist this session's cost so /cost is intact after a relaunch +

            CostStatePersistence.saveForSession(req.sessionId,
                Path.of(req.workingDirectory != null
                    ? req.workingDirectory : System.getProperty("user.dir")));
            printResumeHint(req);

            // hooks. This removes orphaned agent-team state when the leader
            // exits without an explicit TeamDelete.
            TeamSessionCleanup.cleanupRegisteredTeams();
            fireSessionEndHooks(req);
        } catch (Throwable t) {
            log.warn("Graceful shutdown step failed: {}", t.getMessage(), t);
        }
    }

    /**
     * Print the resume hint to the shell after the REPL exits. Suppressed when:
     * <ul>
     *   <li>{@code out} is null (non-interactive / test contexts),</li>
     *   <li>no sessionId available (subcommands like {@code claude mcp add}),</li>
     *   <li>the session file was never written (empty session).</li>
     * </ul>
     */
    private static void printResumeHint(Request req) {
        if (req.resumeHintSink == null) return;
        if (StringUtils.isBlank(req.sessionId)) return;
        if (req.sessionManager == null) return;
        try {
            var file = req.sessionManager.getSessionFile(req.sessionId);
            if (!Files.exists(file)) return;
            String title = req.sessionManager.readCustomTitle(req.sessionId);
            String arg = (StringUtils.isNotBlank(title))
                ? "\"" + title.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : req.sessionId;
            req.resumeHintSink.accept("");
            req.resumeHintSink.accept("Resume this session with:");
            req.resumeHintSink.accept("claude --resume " + arg);
        } catch (Throwable t) {
            log.debug("printResumeHint suppressed: {}", t.getMessage());
        }
    }

    /**
     * Fire {@code SessionEnd} hooks with the supplied reason. Delegates to the
     * dispatcher's {@link HookDispatcher#dispatchSessionEnd(String)} which
     * already runs each hook on a virtual thread and caps them at the
     * SessionEnd timeout ({@code getSessionEndHookTimeoutSeconds}, default 1.5s).
     */
    private static void fireSessionEndHooks(Request req) {
        if (req.hookDispatcher == null) return;
        try {
            req.hookDispatcher.dispatchSessionEnd(req.reason);
        } catch (Throwable t) {
            log.warn("SessionEnd hooks threw: {}", t.getMessage());
        }
    }

    /**
     * Failsafe: guarantees the process exits if a hook (or something else)
     * hangs. Budget = {@code max(5s, hookTimeoutMs + 3.5s)}. On fire we call
     * {@link Runtime#halt(int)} — {@code System.exit} would run shutdown hooks
     * again and might deadlock on the same hang.
     */
    private static FailsafeTimer armFailsafe(Request req) {
        long hookMs = req.sessionEndHookTimeoutMs > 0
            ? req.sessionEndHookTimeoutMs
            : 1_500;  // matches HookEngine.SESSION_END_HOOK_TIMEOUT_MS_DEFAULT
        long budget = Math.max(FAILSAFE_MIN_MS, hookMs + FAILSAFE_HEADROOM_MS);

        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "graceful-shutdown-failsafe");
            t.setDaemon(true);
            return t;
        });
        exec.setRemoveOnCancelPolicy(true);
        try {
            ScheduledFuture<?> task = exec.schedule(() -> {
                log.warn("Graceful shutdown exceeded {}ms budget; halting", budget);
                Runtime.getRuntime().halt(req.exitCode);
            }, budget, TimeUnit.MILLISECONDS);
            return new FailsafeTimer(exec, task);
        } catch (RuntimeException | Error failure) {
            exec.shutdownNow();
            throw failure;
        }
    }

    /**
     * Owns both halves of the failsafe: cancelling the delayed halt and stopping
     * its executor. Using the raw executor's {@code close} would perform an
     * orderly shutdown and could wait for the delayed halt to execute.
     */
    private record FailsafeTimer(ScheduledThreadPoolExecutor executor,
                                 ScheduledFuture<?> task) implements AutoCloseable {
        @Override
        public void close() {
            task.cancel(false);
            executor.shutdownNow();
        }
    }

    // ── Request builder ──────────────────────────────────────────────────

    public static final class Request {
        String reason = "other";
        int exitCode = 0;
        String sessionId;
        String workingDirectory;
        HookDispatcher hookDispatcher;
        SessionManager sessionManager;
        Consumer<String> resumeHintSink;
        long sessionEndHookTimeoutMs = 1_500;

        public static Request of(String reason) {
            Request r = new Request();
            r.reason = reason;
            return r;
        }

        public Request exitCode(int code)          { this.exitCode = code; return this; }
        public Request sessionId(String id)        { this.sessionId = id; return this; }
        public Request workingDirectory(String cwd) { this.workingDirectory = cwd; return this; }
        public Request hookDispatcher(HookDispatcher d) { this.hookDispatcher = d; return this; }
        public Request sessionManager(SessionManager m) { this.sessionManager = m; return this; }
        public Request resumeHintSink(Consumer<String> sink) { this.resumeHintSink = sink; return this; }
        public Request sessionEndHookTimeoutMs(long ms) { this.sessionEndHookTimeoutMs = ms; return this; }

        public String reason() { return reason; }
    }
}
