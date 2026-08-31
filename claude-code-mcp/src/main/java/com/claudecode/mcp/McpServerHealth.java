package com.claudecode.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.SubprocessEnvironment;

/**
 * One-shot connectivity probe for {@code claude mcp list} / {@code claude mcp get}.
 */
public final class McpServerHealth {

    private static final long TOOLS_LIST_TIMEOUT_MS = 5_000;
    private static final Map<String, String> PROCESS_ENVIRONMENT =
        SubprocessEnvironment.snapshot();


    public static final String CONNECTED  = tickFor(Platform.IS_WINDOWS, PROCESS_ENVIRONMENT)
        + " Connected";
    public static final String NEEDS_AUTH = "! Needs authentication";
    public static final String TOOLS_FETCH_FAILED = "! Connected · tools fetch failed";
    public static final String FAILED     = crossFor(Platform.IS_WINDOWS, PROCESS_ENVIRONMENT)
        + " Failed to connect";
    public static final String ERROR      = crossFor(Platform.IS_WINDOWS, PROCESS_ENVIRONMENT)
        + " Connection error";

    private static final int  DEFAULT_BATCH_SIZE = 3;

    private McpServerHealth() {}

    /**
     * Connects to one server and classifies the outcome, always disconnecting
     * before returning. Never throws: a probe failure <em>is</em> the result.
     */
    public record HealthResult(String status, String issue) {}

    static HealthResult check(McpClientManager manager, McpServerConfig config) {
        if (config.disabled()) {

// Disabled servers are normally filtered from listings. Report the state rather than
            // surfacing McpClientManager's "Cannot connect to disabled" throw.
            return status(FAILED);
        }
        try {
            try {
                runWithTimeout(() -> manager.connect(config), McpTimeouts.connectionTimeoutMillis());
            } catch (Throwable failure) {
                if (McpFailures.isAuthenticationFailure(failure)) return status(NEEDS_AUTH);
                return status(isExpectedConnectionFailure(failure) ? FAILED : ERROR);
            }

            if (manager.serverSupportsTools(config.name())) {
                try {
                    runWithTimeout(() -> manager.verifyToolsForServer(config.name()),
                        TOOLS_LIST_TIMEOUT_MS);
                } catch (Throwable failure) {
                    if (McpFailures.isAuthenticationFailure(failure)) return status(NEEDS_AUTH);
                    return new HealthResult(TOOLS_FETCH_FAILED, issueMessage(failure));
                }
            }
            return status(CONNECTED);
        } finally {
            // A timed-out stdio connect leaves a blocked reader thread and a
            // live child process; disconnect force-closes the transport.
            try {
                manager.disconnect(config.name());
            } catch (RuntimeException _) {
                // Best-effort cleanup — the status is already decided.
            }
        }
    }

    /**
     * Probes every server concurrently, bounded by
     * {@code MCP_SERVER_CONNECTION_BATCH_SIZE} (default 3), preserving the
     * iteration order of {@code servers} in the returned map.
     *
     * <p>Each probe gets its own {@link McpClientManager} so that a stdio
     * server wedging its transport cannot stall a sibling, and so no probe
     * observes another's connection state.
     */
    public static Map<String, String> checkAll(Map<String, McpServerConfig> servers) {
        Map<String, String> statuses = new LinkedHashMap<>();
        if (servers == null || servers.isEmpty()) return statuses;

        Semaphore permits = new Semaphore(batchSize());
        Map<String, CompletableFuture<String>> pending = new LinkedHashMap<>();
        for (Map.Entry<String, McpServerConfig> entry : servers.entrySet()) {
            McpServerConfig config = entry.getValue();
            pending.put(entry.getKey(), CompletableFuture.supplyAsync(() -> {
                permits.acquireUninterruptibly();
                McpClientManager manager = new McpClientManager();
                try {
                    return check(manager, config).status();
                } finally {
                    permits.release();
                    closeQuietly(manager);
                }
            }, task -> Thread.ofVirtual().name("mcp-health-" + config.name()).start(task)));
        }

        for (Map.Entry<String, CompletableFuture<String>> entry : pending.entrySet()) {
            String status;
            try {
                status = entry.getValue().join();
            } catch (RuntimeException _) {
                status = ERROR;
            }
            statuses.put(entry.getKey(), status);
        }
        return statuses;
    }

    /** Single-server detailed probe used by {@code mcp get}. */
    public static HealthResult checkOneDetailed(McpServerConfig config) {
        McpClientManager manager = new McpClientManager();
        try {
            return check(manager, config);
        } catch (RuntimeException _) {
            return status(ERROR);
        } finally {
            closeQuietly(manager);
        }
    }

    private static HealthResult status(String status) {
        return new HealthResult(status, null);
    }

    private static boolean isExpectedConnectionFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof McpException || current instanceof TimeoutException) return true;
        }
        return false;
    }

    private static String issueMessage(Throwable failure) {
        String message = failure.getMessage();
        String raw = StringUtils.isNotBlank(message) ? message : failure.toString();
        return raw.replaceAll("\\s+", " ").trim();
    }

    private static void closeQuietly(McpClientManager manager) {
        try {
            manager.close();
        } catch (Exception _) {
            // Probe teardown must never mask the status we came for.
        }
    }

    /**
     * Runs a blocking connect on a virtual thread so a wedged transport cannot
     * hang the CLI forever. matches the {@code orTimeout} guard the tool
     * provider puts around the same call.
     */
    private static void runWithTimeout(Runnable action, long timeoutMs) throws Throwable {
        CompletableFuture<Void> future = CompletableFuture.runAsync(action,
            task -> Thread.ofVirtual().start(task));
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw e.getCause() != null ? e.getCause() : e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** {@code MCP_SERVER_CONNECTION_BATCH_SIZE} env override, else 3. */
    static int batchSize() {
        return (int) positiveEnv("MCP_SERVER_CONNECTION_BATCH_SIZE", DEFAULT_BATCH_SIZE);
    }


    private static long positiveEnv(String name, long fallback) {
        try {
            String raw = SubprocessEnvironment.get(name);
            if (StringUtils.isNotBlank(raw)) {
                long parsed = Long.parseLong(raw.trim());
                if (parsed > 0) return parsed;
            }
        } catch (NumberFormatException _) {

        }
        return fallback;
    }

    static String tickFor(boolean windows, Map<String, String> environment) {
        return usesMainFigures(windows, environment) ? "✔" : "√";
    }

    static String crossFor(boolean windows, Map<String, String> environment) {
        return usesMainFigures(windows, environment) ? "✘" : "×";
    }


    private static boolean usesMainFigures(boolean windows, Map<String, String> environment) {
        Map<String, String> env = environment == null ? Map.of() : environment;
        String term = env.get("TERM");
        if (!windows) return !Strings.CS.equals("linux", term);
        if (StringUtils.isNotEmpty(env.get("WT_SESSION"))
                || StringUtils.isNotEmpty(env.get("TERMINUS_SUBLIME"))) return true;
        if (Strings.CS.equals("{cmd::Cmder}", env.get("ConEmuTask"))) return true;
        String termProgram = env.get("TERM_PROGRAM");
        if (Strings.CS.equalsAny(termProgram, "Terminus-Sublime", "vscode")) return true;
        if (Strings.CS.equalsAny(term, "xterm-256color", "alacritty", "rxvt-unicode",
                "rxvt-unicode-256color")) return true;
        return Strings.CS.equals("JetBrains-JediTerm", env.get("TERMINAL_EMULATOR"));
    }
}
