package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.config.VersionInfo;
import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.webworker.WebWorkerDirectory;
import com.claudecode.runtime.webworker.WebWorkerRegistration;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lifecycle output {@code claude web stop} and {@code claude web status}
 * produce.
 *
 * <p>Every case here is a state the gateway genuinely reaches, because the
 * commands exist to make states that were previously indistinguishable from
 * each other — running versus not, mine versus someone else's, this build
 * versus an older one — say which they are.
 */
class WebCliLifecycleTest {

    private static final String TOKEN = "f7b283615c6af944074a64bf4cb2044158a2761ec590d7bd02b4233a76efac9e";
    private static final String URL = "http://127.0.0.1:19845/?token=" + TOKEN;
    private static final String GATEWAY_VERSION = "0.2.14-7-g75547cce";

    @TempDir
    Path home;

    private final StringWriter captured = new StringWriter();
    private final CliOutput out = CliOutput.borrowed(new PrintWriter(captured, true));
    private final StringWriter capturedErr = new StringWriter();
    private final CliOutput err = CliOutput.borrowed(new PrintWriter(capturedErr, true));

    private Path recordFile() {
        return home.resolve("web-gateway.json");
    }

    private Path workersDir() {
        return home.resolve("web-workers");
    }

    private void writeRecord(long pid, String version) throws Exception {
        WebGatewayRecord.write(recordFile(), new WebGatewayRecord(
            pid, URL, version, System.currentTimeMillis() - 90_000));
    }

    // ---------------------------------------------------------------- status

    @Test
    void statusWithoutAGatewaySaysSo() {
        assertThat(WebCliLifecycle.status(recordFile(), workersDir(), out)).isZero();

        assertThat(captured.toString()).contains("claude web is not running.");
    }

    @Test
    void statusNamesTheBuildThatIsServing() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);

        assertThat(WebCliLifecycle.status(recordFile(), workersDir(), out)).isZero();

        String status = captured.toString();
        assertThat(status).contains("claude web is running");
        assertThat(status).contains(String.valueOf(ProcessHandle.current().pid()));
        assertThat(status).contains(GATEWAY_VERSION);
    }

    /**
     * The token opens the UI. Printing it is the whole point of {@code claude
     * web}'s own output, but this line is for reading, and would land in shell
     * history and pasted issue reports.
     */
    @Test
    void statusRedactsTheAccessToken() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);

        WebCliLifecycle.status(recordFile(), workersDir(), out);

        assertThat(captured.toString())
            .doesNotContain(TOKEN)
            .contains("token=<redacted>");
    }

    @Test
    void statusListsRegisteredSessions() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);
        try (WebWorkerDirectory.Registration _ = register(ProcessHandle.current().pid(),
                GATEWAY_VERSION, "/tmp/project")) {
            WebCliLifecycle.status(recordFile(), workersDir(), out);
        }

        assertThat(captured.toString()).contains("sessions (1 registered)", "/tmp/project");
    }

    /**
     * A session whose build differs from the gateway's is the situation that
     * sent a user looking here in the first place, so it is called out rather
     * than left to be spotted in a version column.
     */
    @Test
    void statusFlagsASessionFromADifferentBuild() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);
        try (WebWorkerDirectory.Registration _ = register(ProcessHandle.current().pid(),
                "0.2.14-17-gb6255e69", "/tmp/project")) {
            WebCliLifecycle.status(recordFile(), workersDir(), out);
        }

        assertThat(captured.toString()).contains("differs from the gateway");
    }

    @Test
    void statusDoesNotFlagASessionFromTheSameBuild() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);
        try (WebWorkerDirectory.Registration _ = register(ProcessHandle.current().pid(),
                GATEWAY_VERSION, "/tmp/project")) {
            WebCliLifecycle.status(recordFile(), workersDir(), out);
        }

        assertThat(captured.toString()).doesNotContain("differs from the gateway");
    }

    @Test
    void statusSaysWhenNoSessionIsRegistered() throws Exception {
        writeRecord(ProcessHandle.current().pid(), GATEWAY_VERSION);

        WebCliLifecycle.status(recordFile(), workersDir(), out);

        assertThat(captured.toString()).contains("No sessions are registered");
    }

    // ------------------------------------------------------------ describeReuse

    @Test
    void reuseNamesTheOlderBuildItAttachedTo() {
        WebCliLifecycle.describeReuse(
            new WebGatewayRecord(4242, URL, GATEWAY_VERSION, 0), out);

        assertThat(captured.toString())
            .contains("A claude web gateway is already running.")
            .contains("4242")
            .contains(GATEWAY_VERSION)
            .contains("token=<redacted>")
            .doesNotContain(TOKEN);
    }

    /**
     * A record from before the field existed still has to identify its gateway:
     * a user stuck behind an unidentifiable process is exactly who needs to
     * know one is there.
     */
    @Test
    void reuseSaysSoWhenTheRunningBuildIsUnknown() {
        WebCliLifecycle.describeReuse(
            new WebGatewayRecord(4242, URL, WebGatewayRecord.UNKNOWN_VERSION, 0), out);

        assertThat(captured.toString()).contains("its build is unknown");
    }

    @Test
    void reuseStaysQuietAboutVersionsWhenTheyMatch() {
        WebCliLifecycle.describeReuse(
            new WebGatewayRecord(4242, URL, VersionInfo.version(), 0), out);

        assertThat(captured.toString()).doesNotContain("this jar is");
    }

    @Test
    void reuseStatesThatNoGatewayWasStarted() {
        WebCliLifecycle.describeReuse(
            new WebGatewayRecord(4242, URL, GATEWAY_VERSION, 0), out);

        assertThat(captured.toString())
            .contains("did NOT start a gateway")
            .contains("claude web stop");
    }

    // ---------------------------------------------------------------- detach

    /**
     * {@code claude web -d} hands the terminal back once the gateway serves:
     * the URL to open, where its output goes, and how to end it.
     */
    @Test
    void detachPrintsTheUrlAndHowToStopIt() {
        Path log = home.resolve("web-gateway.log");

        int exit = WebCliLifecycle.detach(() -> new GatewaySupervisorPort.Started(URL), log, out, err);

        assertThat(exit).isZero();
        assertThat(captured.toString())
            .contains(URL)
            .contains(log.toString())
            .contains("claude web stop");
        assertThat(capturedErr.toString()).isEmpty();
    }

    /** A gateway that never came up is a failed command, with the reason on stderr. */
    @Test
    void detachReportsAGatewayThatDidNotStart() {
        Path log = home.resolve("web-gateway.log");

        int exit = WebCliLifecycle.detach(() -> {
            throw new IllegalStateException("claude web did not publish its gateway; see " + log);
        }, log, out, err);

        assertThat(exit).isEqualTo(1);
        assertThat(captured.toString()).isEmpty();
        assertThat(capturedErr.toString()).contains("did not publish").contains(log.toString());
    }

    // ------------------------------------------------------------------ stop

    @Test
    void stopWithoutAGatewayIsNotAnError() {
        assertThat(WebCliLifecycle.stop(recordFile(), out, err)).isZero();

        assertThat(captured.toString()).contains("No claude web gateway is running.");
        assertThat(capturedErr.toString()).isEmpty();
    }

    /**
     * Stopping a gateway that has already exited is the same outcome as
     * stopping one that is running: nothing is serving. A script calling stop
     * should not have to check first.
     */
    @Test
    void stopOnAStaleRecordIsNotAnError() throws Exception {
        WebGatewayRecord.write(recordFile(), new WebGatewayRecord(
            Long.MAX_VALUE - 1, URL, GATEWAY_VERSION, System.currentTimeMillis()));

        assertThat(WebCliLifecycle.stop(recordFile(), out, err)).isZero();

        assertThat(captured.toString()).contains("No claude web gateway is running.");
    }

    /**
     * The real stop path, against a process that is genuinely running. A live
     * child stands in for the gateway: what matters is that a live pid is
     * signalled and observed to exit, not what it was executing.
     */
    @Test
    @Timeout(30)
    void stopEndsALiveOwner() throws Exception {
        Process child = new ProcessBuilder("sleep", "300").start();
        try {
            writeRecord(child.pid(), GATEWAY_VERSION);

            assertThat(WebCliLifecycle.stop(recordFile(), out, err)).isZero();

            assertThat(captured.toString()).contains("Stopped claude web (pid " + child.pid() + ")");
            assertThat(child.isAlive()).isFalse();
            assertThat(child.onExit().get()).isNotNull();
        } finally {
            child.destroyForcibly();
        }
    }

    /**
     * A stop that cannot make the gateway exit must fail loudly and say how to
     * finish the job. Reporting success here is what leaves a port bound and a
     * record pointing at it.
     */
    @Test
    @Timeout(60)
    void stopReportsAProcessThatIgnoresTheSignal() throws Exception {
        // A single process that ignores SIGTERM, so the signal genuinely does
        // not end it. A shell trap would not do: the shell forwards the signal
        // and exits, which is a successful stop. The child announces that its
        // handler is installed before the signal is sent — signalling during
        // interpreter startup reaches the default handler and would test
        // nothing.
        Process child = new ProcessBuilder("python3", "-u", "-c",
            """
            import signal, time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            print('ready', flush=True)
            time.sleep(300)
            """)
            .redirectErrorStream(true)
            .start();
        try {
            assertThat(child.inputReader().readLine())
                .describedAs("the child must install its handler before we signal it")
                .isEqualTo("ready");
            writeRecord(child.pid(), GATEWAY_VERSION);

            assertThat(WebCliLifecycle.stop(recordFile(), out, err)).isEqualTo(1);

            assertThat(capturedErr.toString())
                .contains("did not stop within")
                .contains("kill -9 " + child.pid());
        } finally {
            child.destroyForcibly();
        }
    }

    /** Government of the record after a successful stop: nothing should linger. */
    @Test
    @Timeout(30)
    void stopRemovesTheDiscoveryRecord() throws Exception {
        Process child = new ProcessBuilder("sleep", "300").start();
        try {
            writeRecord(child.pid(), GATEWAY_VERSION);

            WebCliLifecycle.stop(recordFile(), out, err);

            assertThat(recordFile()).doesNotExist();
        } finally {
            child.destroyForcibly();
        }
    }

    // -------------------------------------------------------------- redaction

    @Test
    void redactionKeepsEverythingAroundTheToken() {
        assertThat(WebCliLifecycle.redactToken("http://127.0.0.1:19845/?token=abc&x=1"))
            .isEqualTo("http://127.0.0.1:19845/?token=<redacted>&x=1");
    }

    @Test
    void redactionLeavesAUrlWithoutATokenAlone() {
        assertThat(WebCliLifecycle.redactToken("http://127.0.0.1:19845/"))
            .isEqualTo("http://127.0.0.1:19845/");
    }

    @Test
    void redactionHandlesABlankUrl() {
        assertThat(WebCliLifecycle.redactToken(null)).isEmpty();
        assertThat(WebCliLifecycle.redactToken("  ")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private WebWorkerDirectory.Registration register(long pid, String version, String workDir)
            throws Exception {
        Path socket = home.resolve("socket-" + pid);
        Files.writeString(socket, "");
        return new WebWorkerDirectory(workersDir()).register(new WebWorkerRegistration(
            pid, socket.toString(), TOKEN, workDir, version));
    }
}