package com.claudecode.cli;

import com.claudecode.core.config.VersionInfo;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * The observable half of {@code claude web}'s lifecycle: what is running, how to
 * stop it, and what a reused gateway means.
 *
 * <p>The gateway is a singleton per user — a second {@code claude web} attaches to
 * the first rather than starting its own — which is the right behavior and the
 * wrong silence. Attaching and starting both printed one URL and exited 0, so a
 * user who upgraded and re-ran the command had no way to learn that the browser
 * was still being served by the old process. Every fact these commands print is
 * already recorded ({@link WebGatewayRecord}, {@link WebWorkerDirectory}); what was
 * missing was any way to ask.
 *
 * <p>The gateway itself is a forwarding process: it owns no session and no
 * transcript. Stopping it therefore takes down the web view and nothing else, and
 * the TUI processes it was serving keep running and re-register with whatever
 * gateway starts next.
 */
final class WebCliLifecycle {

    /** How long a stopped gateway gets to release its port and remove its record. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final long POLL_MILLIS = 100;

    private WebCliLifecycle() {
    }

    /**
     * Stops the running gateway, if any.
     *
     * <p>Reports "nothing to do" rather than an error when none is running: a stop
     * that leaves things stopped has succeeded, and a script should not have to
     * check first.
     *
     * @return 0 when the gateway is stopped or was never running, 1 when one is
     *         running and did not stop in time
     */
    static int stop(Path recordFile, CliOutput out, CliOutput err) {
        Optional<WebGatewayRecord> live = WebGatewayRecord.readLive(recordFile);
        if (live.isEmpty()) {
            out.println("No claude web gateway is running.");
            return 0;
        }
        WebGatewayRecord gateway = live.get();
        Optional<ProcessHandle> handle = ProcessHandle.of(gateway.pid());
        if (handle.isEmpty() || !handle.get().isAlive()) {
            // readLive already treats a dead owner as stale, so this is a race
            // rather than a state worth reporting as a failure.
            out.println("No claude web gateway is running.");
            return 0;
        }

        // SIGTERM, not destroyForcibly: the gateway's shutdown hook is what closes
        // the server and removes the record, and killing past it would leave both
        // behind for the next start to trip over.
        handle.get().destroy();
        if (!awaitGone(handle.get())) {
            err.println("claude web (pid " + gateway.pid() + ") did not stop within "
                + STOP_TIMEOUT.toSeconds() + "s.");
            err.println("It may be wedged; 'kill -9 " + gateway.pid() + "' will end it,"
                + " but its discovery record will need 'rm " + recordFile + "' afterwards.");
            return 1;
        }

        // The hook removes the record on its way out. This covers a gateway that
        // exited between the signal and the hook, so a stopped gateway never
        // leaves a record pointing at a port nothing is listening on.
        WebGatewayRecord.removeIfOwnedBy(recordFile, gateway.pid());
        out.println("Stopped claude web (pid " + gateway.pid() + ").");
        return 0;
    }

    /**
     * Reports what is running: the gateway, and the sessions it can serve.
     *
     * <p>Read-only, and deliberately so — it must be safe to run at any time, and
     * it never restarts or repairs what it finds.
     *
     * <p>The access token is redacted. Opening the UI needs it, so {@code claude web}
     * prints the full URL; inspecting the status does not, and an unredacted line
     * here would land in shell history and pasted bug reports.
     */
    static int status(Path recordFile, Path workersDirectory, CliOutput out) {
        Optional<WebGatewayRecord> live = WebGatewayRecord.readLive(recordFile);
        if (live.isEmpty()) {
            out.println("claude web is not running.");
            return 0;
        }
        WebGatewayRecord gateway = live.get();
        out.println("claude web is running");
        out.println("  pid      " + gateway.pid());
        out.println("  version  " + gateway.version());
        out.println("  url      " + redactToken(gateway.url()));
        out.println("  uptime   " + uptime(gateway.startedAtMillis()));

        List<WebWorkerRegistration> workers = new WebWorkerDirectory(workersDirectory).scan();
        if (workers.isEmpty()) {
            out.println();
            out.println("No sessions are registered; start a TUI to serve one.");
            return 0;
        }
        out.println();
        out.println("sessions (" + workers.size() + " registered):");
        workers.stream()
            .sorted(Comparator.comparingLong(WebWorkerRegistration::pid))
            .forEach(worker -> out.println("  " + workerLine(worker, gateway.version())));
        return 0;
    }

    /**
     * Explains that this invocation attached to an existing gateway instead of
     * starting one.
     *
     * <p>This is the case that misled a user into thinking an upgrade had taken
     * effect: the command printed a URL and exited 0, exactly as a successful start
     * does, while the browser kept talking to the older process. Naming the version
     * the running gateway was built from is what makes the difference visible.
     */
    static void describeReuse(WebGatewayRecord gateway, CliOutput out) {
        out.println("A claude web gateway is already running.");
        out.println("  pid      " + gateway.pid());
        out.println("  url      " + redactToken(gateway.url()));
        out.println("  version  " + gateway.version());

        String mine = VersionInfo.version();
        if (StringUtils.isBlank(gateway.version())
                || WebGatewayRecord.UNKNOWN_VERSION.equals(gateway.version())) {
            out.println("  (its build is unknown, so it may predate this one)");
        } else if (!Strings.CS.equals(gateway.version(), mine)) {
            out.println("  this jar is " + mine);
        }

        out.println();
        out.println("This process did NOT start a gateway, and is exiting; the running one"
            + " keeps serving, so a browser already open on it is running that version's code.");
        out.println("Run 'claude web stop' to replace it, or 'claude web status' for details.");
    }

    /**
     * Starts the gateway in a process of its own and returns once it serves
     * ({@code claude web -d}).
     *
     * <p>The foreground form blocks the terminal for the gateway's whole life,
     * which is the wrong trade for a process that owns no session: closing that
     * terminal would take the web view down with it. Here the gateway is the
     * same detached {@code claude web} child a TUI's {@code /web} launches, so
     * this prints what the foreground form would have — the URL to open — plus
     * where the child's output now goes and how to end it.
     *
     * @return 0 once the gateway serves, 1 when it did not come up
     */
    static int detach(GatewaySupervisorPort gateway, Path logFile, CliOutput out, CliOutput err) {
        GatewaySupervisorPort.Started started;
        try {
            started = gateway.start();
        } catch (IllegalStateException failure) {
            err.println(failure.getMessage());
            return 1;
        }
        out.println(started.url());
        out.println();
        out.println("claude web is running in the background; its output goes to " + logFile + ".");
        out.println("Run 'claude web stop' to end it, or 'claude web status' to inspect it.");
        return 0;
    }

    // ------------------------------------------------------------- internals

    private static boolean awaitGone(ProcessHandle handle) {
        long deadline = System.nanoTime() + STOP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!handle.isAlive()) return true;
            sleepPoll();
        }
        return !handle.isAlive();
    }

    private static void sleepPoll() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** A worker line, marked when its build differs from the gateway's. */
    private static String workerLine(WebWorkerRegistration worker, String gatewayVersion) {
        String version = StringUtils.defaultIfBlank(worker.version(), WebGatewayRecord.UNKNOWN_VERSION);
        String note = Strings.CS.equals(version, gatewayVersion) ? "" : "  (differs from the gateway)";
        return worker.pid() + "  " + version + "  " + worker.workDir() + note;
    }

    /** Keeps the scheme, host and port; the token is a live credential. */
    static String redactToken(String url) {
        if (StringUtils.isBlank(url)) return "";
        int token = url.indexOf("token=");
        if (token < 0) return url;
        int end = url.indexOf('&', token);
        String suffix = end < 0 ? "" : url.substring(end);
        return url.substring(0, token) + "token=<redacted>" + suffix;
    }

    private static String uptime(long startedAtMillis) {
        if (startedAtMillis <= 0) return "unknown";
        Duration elapsed = Duration.between(Instant.ofEpochMilli(startedAtMillis), Instant.now());
        if (elapsed.isNegative()) return "unknown";
        long days = elapsed.toDays();
        long hours = elapsed.toHoursPart();
        long minutes = elapsed.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
