package com.claudecode.tools.tasks.teammate;

import com.claudecode.core.engine.AbortController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class TeammateContextHolderTest {

    @AfterEach
    void clearLeakedContext() {
        TeammateContextHolder.clear();
    }

    private static TeammateContext sampleContext(String agentId) {
        return TeammateContext.builder()
            .agentId(agentId)
            .teamId("team-1")
            .abortController(new AbortController())
            .build();
    }

    @Test
    void setThenGetReturnsSameContext() {
        TeammateContext ctx = sampleContext("a1");
        TeammateContextHolder.set(ctx);
        assertSame(ctx, TeammateContextHolder.get());
    }

    @Test
    void clearRemovesContext() {
        TeammateContextHolder.set(sampleContext("a1"));
        TeammateContextHolder.clear();
        assertNull(TeammateContextHolder.get());
    }

    @Test
    void runWithContextExposesContextDuringAction() {
        TeammateContext ctx = sampleContext("a1");
        TeammateContext[] seen = new TeammateContext[1];
        TeammateContextHolder.runWithContext(ctx, () -> seen[0] = TeammateContextHolder.get());
        assertSame(ctx, seen[0]);
    }

    @Test
    void runWithContextClearsEvenWhenActionThrows() {
        TeammateContext ctx = sampleContext("a1");
        // runWithContext must not swallow the action's exception, but its
        // finally block must still clear the context (or, if a context was
        // active, restore the previous one) before the exception escapes.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> TeammateContextHolder.runWithContext(ctx, () -> {
                throw new IllegalStateException("boom");
            }),
            "runWithContext must propagate the action's exception");
        assertEquals("boom", thrown.getMessage());
        // The finally block must have cleared the context.
        assertNull(TeammateContextHolder.get(), "context must be cleared after a throwing action");
    }

    @Test
    void runWithContextReturnsContextToPreviousValue() {
        TeammateContext outer = sampleContext("outer");
        TeammateContextHolder.set(outer);
        try {
            TeammateContext inner = sampleContext("inner");
            TeammateContextHolder.runWithContext(inner, () -> {
                assertSame(inner, TeammateContextHolder.get());
            });
            // After runWithContext returns, the outer context is restored.
            assertSame(outer, TeammateContextHolder.get());
        } finally {
            TeammateContextHolder.clear();
        }
    }

    @Test
    void withContextExecutorRunsActionWithContext() {
        TeammateContext ctx = sampleContext("a1");
        TeammateContext[] seen = new TeammateContext[1];
        var executor = TeammateContextHolder.withContext(ctx, Runnable::run);
        executor.execute(() -> seen[0] = TeammateContextHolder.get());
        assertSame(ctx, seen[0]);
        // The wrapper must clear after the task completes.
        assertNull(TeammateContextHolder.get());
    }

    @Test
    void contextPropagatesToChildVirtualThread() throws InterruptedException {
        TeammateContext ctx = sampleContext("a1");
        TeammateContextHolder.set(ctx);
        try {
            TeammateContext[] seen = new TeammateContext[1];
            Thread child = Thread.ofVirtual().unstarted(
                () -> seen[0] = TeammateContextHolder.get());
            child.start();
            child.join(2000);
            assertSame(ctx, seen[0],
                "InheritableThreadLocal must propagate the teammate context to child virtual threads");
        } finally {
            TeammateContextHolder.clear();
        }
    }

    @Test
    void clearedContextIsNotLeakedIntoChildVirtualThread() throws InterruptedException {
        TeammateContextHolder.set(sampleContext("a1"));
        TeammateContextHolder.clear();
        TeammateContext[] seen = new TeammateContext[1];
        Thread child = Thread.ofVirtual().unstarted(
            () -> seen[0] = TeammateContextHolder.get());
        child.start();
        child.join(2000);
        assertNull(seen[0], "a cleared context must not leak into subsequently spawned threads");
    }
}
