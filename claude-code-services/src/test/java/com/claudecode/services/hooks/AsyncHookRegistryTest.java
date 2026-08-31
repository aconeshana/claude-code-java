package com.claudecode.services.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.AsyncHookResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Characterizes the per-session async-hook lifecycle.
 */
class AsyncHookRegistryTest {

    @Test
    void completedResponsesAreIsolatedPerRegistryInstanceAndDeliveredOnce() {
        AsyncHookRegistry first = new AsyncHookRegistry();
        AsyncHookRegistry second = new AsyncHookRegistry();
        String processId = first.register(
            "Stop", "hook command", "Stop", null, null, 15_000, new RecordingProcess());

        first.complete(processId, "{\"decision\":\"allow\"}\n", "", 0,
            AsyncHookRegistry.AsyncStatus.COMPLETED);

        assertEquals(List.of(), second.checkForAsyncHookResponses(),
            "one HookEngine session must not observe another session's background hook");

        List<AsyncHookResponse> responses = first.checkForAsyncHookResponses();
        assertEquals(1, responses.size());
        assertEquals(processId, responses.getFirst().processId());
        assertEquals("{\"decision\":\"allow\"}", responses.getFirst().responseJson());
        assertEquals(List.of(), first.checkForAsyncHookResponses(),
            "polling must not deliver the same completed response twice");
    }

    @Test
    void removeDeliveredAsyncHooksDoesNotAffectOtherPendingHooks() {
        AsyncHookRegistry registry = new AsyncHookRegistry();
        String removed = registry.register(
            "Stop", "first", "Stop", null, null, 15_000, new RecordingProcess());
        String retained = registry.register(
            "Stop", "second", "Stop", null, null, 15_000, new RecordingProcess());

        registry.removeDeliveredAsyncHooks(List.of(removed));
        registry.complete(retained, "{\"decision\":\"allow\"}\n", "", 0,
            AsyncHookRegistry.AsyncStatus.COMPLETED);

        List<AsyncHookResponse> responses = registry.checkForAsyncHookResponses();
        assertEquals(1, responses.size());
        assertEquals(retained, responses.getFirst().processId());
    }

    @Test
    void finalizeTerminatesAndClearsOnlyThisRegistrysPendingHooks() {
        AsyncHookRegistry first = new AsyncHookRegistry();
        AsyncHookRegistry second = new AsyncHookRegistry();
        RecordingProcess firstProcess = new RecordingProcess();
        RecordingProcess secondProcess = new RecordingProcess();
        first.register("Stop", "first", "Stop", null, null, 15_000, firstProcess);
        String secondId = second.register("Stop", "second", "Stop", null, null, 15_000, secondProcess);

        first.finalizePendingAsyncHooks();

        assertTrue(firstProcess.destroyed);
        assertFalse(secondProcess.destroyed);
        assertEquals(List.of(), first.checkForAsyncHookResponses());

        second.complete(secondId, "{\"decision\":\"allow\"}\n", "", 0,
            AsyncHookRegistry.AsyncStatus.COMPLETED);
        assertEquals(1, second.checkForAsyncHookResponses().size(),
            "finalizing one HookEngine must not clear a sibling engine's registry");
    }

    private static final class RecordingProcess extends Process {
        private boolean alive = true;
        private boolean destroyed;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
