package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.claudecode.runtime.gateway.GatewaySupervisorPort;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Start semantics of the interactive web-gateway runtime: idempotent starts,
 * single-flight sharing between the startup warmup and {@code /web}, and the
 * retry path after a failed start. These tests bind real loopback sockets —
 * the port-probing and double-binding hazards they guard against only exist
 * against the real {@code HttpServer}.
 */
class CliGatewayRuntimeStartTest {

    private CliGatewayRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.close();
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
        runtime = new CliGatewayRuntime(registry());

        GatewaySupervisorPort.Started first = runtime.start();
        GatewaySupervisorPort.Started second = runtime.start();

        assertThat(second.url()).isEqualTo(first.url());
        assertThat(runtime.startAsync().join().url()).isEqualTo(first.url());
    }

    @Test
    @Timeout(20)
    void startAsyncWarmupSharesOneFlightWithConcurrentStart() throws Exception {
        runtime = new CliGatewayRuntime(registry());

        CompletableFuture<GatewaySupervisorPort.Started> warmup = runtime.startAsync();
        // /web racing the warmup: it must join the same in-flight start
        // rather than binding a second port.
        GatewaySupervisorPort.Started viaWeb = runtime.start();

        assertThat(warmup.get(10, TimeUnit.SECONDS).url()).isEqualTo(viaWeb.url());
        assertThat(viaWeb.url()).startsWith("http://127.0.0.1:");
    }

    @Test
    @Timeout(20)
    void startAfterExhaustedPortsFailsThenRetriesWhenPortFrees() throws Exception {
        runtime = new CliGatewayRuntime(registry());
        // Occupy the whole probe range so the first start fails. Ports already
        // held by other processes (a running TUI's gateway, this test JVM's
        // own listener) need no blocker: they are busy for the probe anyway.
        List<ServerSocket> blockers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ServerSocket blocker = new ServerSocket();
            blocker.setReuseAddress(true);
            try {
                blocker.bind(new InetSocketAddress("127.0.0.1", 8087 + i));
                blockers.add(blocker);
            } catch (IOException _) {
                try { blocker.close(); } catch (IOException _) { /* already closed */ }
            }
        }
        try {
            CompletableFuture<GatewaySupervisorPort.Started> failed = runtime.startAsync();
            assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to start web gateway");
            // The failed flight must clear so /web can retry after the ports free.
            for (ServerSocket blocker : blockers) blocker.close();
            assertThat(runtime.start().url()).startsWith("http://127.0.0.1:");
        } finally {
            for (ServerSocket blocker : blockers) {
                try { blocker.close(); } catch (Exception _) { /* already closed */ }
            }
        }
    }
}
