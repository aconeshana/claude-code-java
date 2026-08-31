package com.claudecode.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tests the stable precedence between SDK, headless, and interactive launch modes. */
class CliExecutionRouterTest {

    @Test
    void sdkInputWinsOverAllOtherModeFlags() {
        AtomicInteger sdk = new AtomicInteger();
        AtomicInteger headless = new AtomicInteger();
        AtomicInteger interactive = new AtomicInteger();

        int exit = CliExecutionRouter.route(new CliExecutionRouter.Request(
            true, true, true, true,
            () -> { sdk.incrementAndGet(); return 11; },
            () -> { headless.incrementAndGet(); return 22; },
            () -> { interactive.incrementAndGet(); return 33; }));

        assertEquals(11, exit);
        assertEquals(1, sdk.get());
        assertEquals(0, headless.get());
        assertEquals(0, interactive.get());
    }

    @Test
    void noInteractiveWithoutPromptExitsWithoutConstructingARepl() {
        AtomicInteger interactive = new AtomicInteger();

        int exit = CliExecutionRouter.route(new CliExecutionRouter.Request(
            false, false, true, false,
            () -> 11,
            () -> 22,
            () -> { interactive.incrementAndGet(); return 33; }));

        assertEquals(0, exit);
        assertEquals(0, interactive.get());
    }

    @Test
    void printAndNoInteractivePromptsUseTheFiniteHeadlessRunner() {
        assertRoute(false, true, false, true, 0, 1, 0, 22);
        assertRoute(false, false, true, true, 0, 1, 0, 22);
    }

    @Test
    void ordinaryPromptUsesTheInteractiveRunner() {
        assertRoute(false, false, false, true, 0, 0, 1, 33);
    }

    private static void assertRoute(
            boolean sdkStreamJson,
            boolean printMode,
            boolean noInteractive,
            boolean hasInitialPrompt,
            int expectedSdk,
            int expectedHeadless,
            int expectedInteractive,
            int expectedExit) {
        AtomicInteger sdk = new AtomicInteger();
        AtomicInteger headless = new AtomicInteger();
        AtomicInteger interactive = new AtomicInteger();

        int exit = CliExecutionRouter.route(new CliExecutionRouter.Request(
            sdkStreamJson, printMode, noInteractive, hasInitialPrompt,
            () -> { sdk.incrementAndGet(); return 11; },
            () -> { headless.incrementAndGet(); return 22; },
            () -> { interactive.incrementAndGet(); return 33; }));

        assertEquals(expectedExit, exit);
        assertEquals(expectedSdk, sdk.get());
        assertEquals(expectedHeadless, headless.get());
        assertEquals(expectedInteractive, interactive.get());
    }
}
