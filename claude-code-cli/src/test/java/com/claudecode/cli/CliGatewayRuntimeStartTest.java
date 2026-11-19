package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Start semantics of the interactive web-gateway runtime: idempotent starts,
 * single-flight sharing between the startup warmup and {@code /web}, and the
 * fixed-port single-instance rule. These tests bind real loopback sockets —
 * the double-binding hazard they guard against only exists against the real
 * {@code HttpServer}.
 *
 * <p>Every test binds through {@link #TEST_PORT}, never the production 19845.
 * The rule under test is "a gateway binds its one configured port, and a
 * second one fails rather than probing upward", and that is fully exercised on
 * any port. Binding the real one would make the suite fail on every machine
 * that is already serving the web UI — the developer's own running gateway, or
 * a TUI's in-process one — turning a correct system state into a red build.
 *
 * <p>The port is still a hard singleton within the class: the tests that
 * occupy it for real (both anti-probing ones) cannot run concurrently with
 * each other. JUnit runs them sequentially by default.
 */
class CliGatewayRuntimeStartTest {

    /**
     * The port these tests bind, chosen from the ephemeral range and passed to
     * the runtime through {@link CliGatewayRuntime#PORT_OVERRIDE_ENV}, which is
     * the only supported way to move it — there is no probing to fall back on.
     */
    private static final int TEST_PORT = 29745;

    private static final String TEST_URL_PREFIX = "http://127.0.0.1:" + TEST_PORT + "/?token=";

    private CliGatewayRuntime runtime;

    @TempDir
    Path claudeHome;

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.close();
    }

    private CliGatewayRuntime isolatedRuntime() {
        CliGatewayRuntime isolated = new CliGatewayRuntime(registry());
        isolated.useWebStateDirectory(claudeHome);
        return isolated;
    }

    @Test
    @Timeout(20)
    void startReusesALiveGlobalGatewayInsteadOfBinding() throws IOException {
        String published = "http://127.0.0.1:1/?token=external";
        WebGatewayRecord.write(claudeHome.resolve("web-gateway.json"),
            new WebGatewayRecord(ProcessHandle.current().pid(), published, "0.2.14", 1_700_000_000_000L));
        runtime = isolatedRuntime();

        assertThat(runtime.start().url()).isEqualTo(published);
    }

    @Test
    @Timeout(20)
    void startPublishesItsOwnGlobalGatewayRecord() throws IOException {
        runtime = isolatedRuntime();

        String url = runtime.start().url();

        WebGatewayRecord record = WebGatewayRecord.readLive(claudeHome.resolve("web-gateway.json"))
            .orElseThrow();
        assertThat(record.url()).isEqualTo(url);
        assertThat(record.pid()).isEqualTo(ProcessHandle.current().pid());
        runtime.close();
        assertThat(claudeHome.resolve("web-gateway.json")).doesNotExist();
    }

    private static SessionHostRegistry registry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    }

    @Test
    @Timeout(20)
    void repeatedStartsReturnTheSameRunningGateway() {
        runtime = isolatedRuntime();

        GatewaySupervisorPort.Started first = runtime.start();
        GatewaySupervisorPort.Started second = runtime.start();

        assertThat(second.url()).isEqualTo(first.url());
        assertThat(runtime.startAsync().join().url()).isEqualTo(first.url());
    }

    @Test
    @Timeout(20)
    void startAsyncWarmupSharesOneFlightWithConcurrentStart() throws Exception {
        runtime = isolatedRuntime();

        CompletableFuture<GatewaySupervisorPort.Started> warmup = runtime.startAsync();
        // /web racing the warmup: it must join the same in-flight start
        // rather than binding a second port.
        GatewaySupervisorPort.Started viaWeb = runtime.start();

        assertThat(warmup.get(10, TimeUnit.SECONDS).url()).isEqualTo(viaWeb.url());
        assertThat(viaWeb.url()).startsWith("http://127.0.0.1:");
    }

    @Test
    @Timeout(20)
    void bindsTheOneFixedPort() {
        runtime = isolatedRuntime();

        String url = runtime.start().url();

        // The port is the single-instance lock, so it must be exactly the one
        // configured and never a neighbour: a gateway that quietly fell back to
        // the next port would be reachable only by whoever read the startup
        // line, and invisible to both the record and the web UI's fixed URL.
        assertThat(url).startsWith(TEST_URL_PREFIX);
    }

    /**
     * The property the fixed port exists to guarantee: a second web gateway
     * cannot come up while one holds the configured port. A peer — another TUI
     * or a standalone {@code claude web} supervisor — is simulated by a plain
     * listener on the port, which is what the second gateway would collide
     * with; the start must fail rather than probe upward.
     */
    @Test
    @Timeout(20)
    void secondGatewayFailsWhileAnotherHoldsThePort() throws Exception {
        try (ServerSocket incumbent = new ServerSocket()) {
            incumbent.setReuseAddress(true);
            incumbent.bind(new InetSocketAddress("127.0.0.1", TEST_PORT));
            runtime = isolatedRuntime();

            CompletableFuture<GatewaySupervisorPort.Started> blocked = runtime.startAsync();

            assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to start web gateway");
        }
        // No gateway bound: a refused start must not leave a record behind that
        // would make `web status` promise a URL nothing is answering.
        assertThat(WebGatewayRecord.readLive(claudeHome.resolve("web-gateway.json")))
            .isEmpty();
    }

    @Test
    @Timeout(20)
    void startRetriesOnTheFixedPortAfterAFailedBind() throws Exception {
        ServerSocket blocker = new ServerSocket();
        blocker.setReuseAddress(true);
        blocker.bind(new InetSocketAddress("127.0.0.1", TEST_PORT));
        runtime = isolatedRuntime();
        try {
            assertThatThrownBy(() -> runtime.start()).hasMessageContaining("failed to start web gateway");
        } finally {
            blocker.close();
        }

        // The failed flight must clear, and the retry must land on the same
        // fixed port rather than drifting to the next free one.
        assertThat(runtime.start().url()).startsWith(TEST_URL_PREFIX);
    }
}
