package com.claudecode.cli.daemon;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaemonWorkerDispatcherTest {

    @Test
    void ignoresOrdinaryCliArguments() {
        assertFalse(DaemonWorkerDispatcher.tryRun(
            new String[]{"--print", "hello"}, Input.none(), Output.none(), Output.none()).isPresent());
    }

    @Test
    void rejectsUnknownWorkerKind() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = DaemonWorkerDispatcher.tryRun(
            new String[]{"--daemon-worker", "unknown"}, Input.of("{}"),
            Output.none(), Output.to(error)).orElseThrow();

        assertEquals(2, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("unknown daemon worker"));
    }

    @Test
    void rejectsInvalidScheduledConfigBeforeStartingWorker() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exit = DaemonWorkerDispatcher.tryRun(
            new String[]{"--daemon-worker", "scheduled"}, Input.of("{\"unknown\":true}"),
            Output.none(), Output.to(error)).orElseThrow();

        assertEquals(2, exit);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("unknown scheduled worker config field"));
    }

    private static final class Input {
        static ByteArrayInputStream of(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
        static ByteArrayInputStream none() { return of(""); }
    }

    private static final class Output {
        static PrintStream none() { return to(new ByteArrayOutputStream()); }
        static PrintStream to(ByteArrayOutputStream target) {
            return new PrintStream(target, true, StandardCharsets.UTF_8);
        }
    }
}
