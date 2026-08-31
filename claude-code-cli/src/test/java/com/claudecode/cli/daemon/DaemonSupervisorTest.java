package com.claudecode.cli.daemon;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonSupervisorTest {

    @Test
    void retriesRateLimitedWorkerThenStopsOnPermanentExit() {
        FakeProcess first = new FakeProcess(75);
        FakeProcess second = new FakeProcess(78);
        ArrayDeque<DaemonWorkerProcess> processes = new ArrayDeque<>(List.of(first, second));
        List<Long> delays = new ArrayList<>();
        DaemonSupervisor supervisor = new DaemonSupervisor(
            _ -> processes.removeFirst(), delays::add, () -> 0.5d, _ -> {});

        supervisor.run("scheduled", "{\"tasks\":[]}");

        assertEquals(List.of(1_000L), delays);
        assertTrue(processes.isEmpty());
    }

    @Test
    void stopEscalatesFromShutdownToTerminateAndKill() {
        FakeProcess process = new FakeProcess(Integer.MIN_VALUE);
        process.shutdownAccepted = false;
        DaemonSupervisor supervisor = new DaemonSupervisor(
            _ -> process, _ -> {}, () -> 1d, _ -> {});
        supervisor.start("scheduled", "{\"tasks\":[]}");
        while (supervisor.currentProcessForTest() == null) Thread.onSpinWait();

        supervisor.close();

        assertTrue(process.terminateCalled);
        assertTrue(process.killCalled);
    }

    private static final class FakeProcess implements DaemonWorkerProcess {
        private final int exitCode;
        private boolean shutdownAccepted = true;
        private boolean terminateCalled;
        private boolean killCalled;
        private volatile boolean awaited;

        private FakeProcess(int exitCode) {
            this.exitCode = exitCode;
        }

        @Override public boolean sendShutdown() { return shutdownAccepted; }
        @Override public int awaitExit() {
            while (exitCode == Integer.MIN_VALUE && !awaited) Thread.onSpinWait();
            return exitCode == Integer.MIN_VALUE ? 0 : exitCode;
        }
        @Override public boolean awaitExit(Duration timeout) { return exitCode != Integer.MIN_VALUE; }
        @Override public void terminate() { terminateCalled = true; }
        @Override public void kill() { killCalled = true; awaited = true; }
        @Override public boolean isAlive() { return exitCode == Integer.MIN_VALUE && !awaited; }
    }
}
