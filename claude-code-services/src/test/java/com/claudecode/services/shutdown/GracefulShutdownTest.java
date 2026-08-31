package com.claudecode.services.shutdown;

import com.claudecode.core.engine.HookDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GracefulShutdownTest {

    @BeforeEach
    @AfterEach
    void resetFlag() {
        GracefulShutdown.resetForTesting();
    }

    @Test
    void run_firesSessionEndHooksWithReason() {
        AtomicReference<String> capturedReason = new AtomicReference<>();
        HookDispatcher stub = new StubHookDispatcher() {
            @Override public void dispatchSessionEnd(String reason) {
                capturedReason.set(reason);
            }
        };
        GracefulShutdown.run(
            GracefulShutdown.Request.of("prompt_input_exit").hookDispatcher(stub));
        assertEquals("prompt_input_exit", capturedReason.get());
    }

    @Test
    void run_isSingleShot() {
        AtomicBoolean fired = new AtomicBoolean(false);
        HookDispatcher stub = new StubHookDispatcher() {
            @Override public void dispatchSessionEnd(String reason) {
                fired.set(true);
            }
        };
        GracefulShutdown.run(GracefulShutdown.Request.of("ctrl_c").hookDispatcher(stub));
        assertTrue(fired.get());

        // Second call must be a no-op.
        fired.set(false);
        GracefulShutdown.run(GracefulShutdown.Request.of("ctrl_c").hookDispatcher(stub));
        assertFalse(fired.get());
    }

    @Test
    void run_toleratesNullHookDispatcher() {
        assertDoesNotThrow(() ->
            GracefulShutdown.run(GracefulShutdown.Request.of("other")));
    }

    @Test
    void run_swallowsHookExceptions() {
        HookDispatcher throwing = new StubHookDispatcher() {
            @Override public void dispatchSessionEnd(String reason) {
                throw new RuntimeException("boom");
            }
        };
        assertDoesNotThrow(() ->
            GracefulShutdown.run(GracefulShutdown.Request.of("sigterm").hookDispatcher(throwing)));
    }

    @Test
    void isShuttingDown_reflectsFlag() {
        assertFalse(GracefulShutdown.isShuttingDown());
        GracefulShutdown.run(GracefulShutdown.Request.of("ctrl_c"));
        assertTrue(GracefulShutdown.isShuttingDown());
    }

    /** Base test double — all methods no-op unless overridden. */
    private static class StubHookDispatcher implements HookDispatcher {
        @Override public boolean dispatchPreToolUse(String toolName, JsonNode input, String toolUseId) { return true; }
        @Override public void dispatchPostToolUse(String toolName, JsonNode input, JsonNode output, String toolUseId) {}
        @Override public void dispatchUserPromptSubmit(String prompt) {}
        @Override public void dispatchSessionStart(String trigger) {}
        @Override public void dispatchStop(String reason) {}
    }
}
