package com.claudecode.ui.lanterna.repl;

import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.ui.lanterna.dialog.WorktreeExitDialog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exit gesture and worktree-shutdown state-machine coverage. */
class ReplExitControllerTest {

    @Test
    void ctrlCPrioritizesBashThenTurnThenInputBeforeArmingExit() {
        FakeActions actions = new FakeActions();
        actions.bashRunning = true;
        AtomicLong clock = new AtomicLong(1_000);
        Fixture fixture = new Fixture(actions, clock);

        fixture.controller.handleCtrlC();
        assertEquals(1, actions.bashInterrupts);
        assertTrue(actions.hints.isEmpty());

        actions.bashRunning = false;
        actions.turnRunning = true;
        fixture.controller.handleCtrlC();
        assertEquals(1, actions.turnInterrupts);

        actions.turnRunning = false;
        actions.inputPresent = true;
        fixture.controller.handleCtrlC();
        assertEquals(1, actions.inputClears);
        assertTrue(fixture.shutdowns.isEmpty());
    }

    @Test
    void ctrlCDoublePressRequestsPromptExitWithinEightHundredMilliseconds() {
        FakeActions actions = new FakeActions();
        AtomicLong clock = new AtomicLong(1_000);
        Fixture fixture = new Fixture(actions, clock);

        fixture.controller.handleCtrlC();
        assertEquals(List.of("Press Ctrl+C again to exit"), actions.hints);
        clock.set(1_799);
        fixture.controller.handleCtrlC();

        assertEquals(List.of("prompt_input_exit:0"), fixture.shutdowns);
        assertEquals(1, fixture.stops.get());
    }

    @Test
    void ctrlDUsesItsDoublePressExitStateEvenDuringActiveTurn() {
        FakeActions actions = new FakeActions();
        actions.turnRunning = true;
        AtomicLong clock = new AtomicLong(2_000);
        Fixture fixture = new Fixture(actions, clock);

        fixture.controller.handleCtrlD();
        assertEquals(List.of("Press Ctrl+D again to exit"), actions.hints);
        clock.set(2_700);
        fixture.controller.handleCtrlD();

        assertEquals(List.of("prompt_input_exit:0"), fixture.shutdowns);
        assertEquals(1, actions.softInterrupts);
    }

    @Test
    void interactiveWorktreeExitWaitsForDialogAndPersistsOnlyWhenProceeding() {
        FakeActions actions = new FakeActions();
        AtomicReference<Consumer<WorktreeExitDialog.Result>> callback = new AtomicReference<>();
        AtomicInteger persisted = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        List<String> shutdowns = new ArrayList<>();
        WorktreeSession session = new WorktreeSession(
            "/project", "/project/.claude/worktrees/x", "x", "branch-x", "main", "abc",
            "session-1", null, false, 0, false);
        ReplExitController controller = new ReplExitController(
            (reason, code) -> shutdowns.add(reason + ":" + code),
            actions,
            () -> session,
            (_, result) -> callback.set(result),
            _ -> persisted.incrementAndGet(),
            _ -> {},
            stops::incrementAndGet,
            _ -> {},
            System::currentTimeMillis);

        controller.requestShutdown("prompt_input_exit", 0);
        assertTrue(shutdowns.isEmpty());
        callback.get().accept(new WorktreeExitDialog.Result("Exit cancelled", false));
        assertTrue(shutdowns.isEmpty());
        assertEquals(0, persisted.get());

        controller.requestShutdown("prompt_input_exit", 0);
        callback.get().accept(new WorktreeExitDialog.Result("Kept worktree", true));
        assertEquals(List.of("prompt_input_exit:0"), shutdowns);
        assertEquals(1, persisted.get());
        assertEquals(1, stops.get());
    }

    @Test
    void terminationSignalSoftInterruptsBeforeTranscriptShutdown() {
        FakeActions actions = new FakeActions();
        actions.turnRunning = true;
        AtomicLong clock = new AtomicLong(1_000);
        Fixture fixture = new Fixture(actions, clock);

        fixture.controller.handleTerminationSignal("sigterm", 143);

        assertEquals(1, actions.softInterrupts);
        assertEquals(List.of("sigterm:143"), fixture.shutdowns);
        assertEquals(1, fixture.stops.get());
    }

    @Test
    void committedPromptExitSoftInterruptsBeforeTranscriptShutdown() {
        FakeActions actions = new FakeActions();
        actions.turnRunning = true;
        Fixture fixture = new Fixture(actions, new AtomicLong(1_000));

        fixture.controller.requestShutdown("prompt_input_exit", 0);

        assertEquals(1, actions.softInterrupts);
        assertEquals(List.of("prompt_input_exit:0"), fixture.shutdowns);
        assertEquals(1, fixture.stops.get());
    }

    @Test
    void committedExitPersistsSessionThenReleasesTerminalBeforePrintingShutdownOutput() {
        List<String> events = new ArrayList<>();
        ReplExitController controller = new ReplExitController(
            new ShutdownPort() {
                @Override public void prepare(String reason, int code) {
                    events.add("session:prepare");
                }
                @Override public void shutdown(String reason, int code) {
                    events.add("shutdown:" + reason);
                }
            },
            new FakeActions(),
            () -> null,
            null,
            _ -> {},
            _ -> {},
            () -> events.add("terminal:stop"),
            _ -> {},
            System::currentTimeMillis);

        controller.requestShutdown("prompt_input_exit", 0);

        assertEquals(List.of(
            "session:prepare", "terminal:stop", "shutdown:prompt_input_exit"), events);
    }

    @Test
    void jobControlSuspendCleansTerminalBeforeStoppingAndRestoresOnContinue() {
        List<String> events = new ArrayList<>();
        FakeActions actions = new FakeActions();
        ReplExitController controller = new ReplExitController(
            ShutdownPort.noop(),
            actions,
            () -> null,
            null,
            _ -> {},
            _ -> {},
            () -> {},
            new ReplExitController.JobControlActions() {
                @Override public void beforeSuspend() { events.add("terminal:suspend"); }
                @Override public void afterResume() { events.add("terminal:resume"); }
            },
            () -> events.add("process:stop"),
            _ -> {},
            System::currentTimeMillis);

        controller.handleJobControlSuspend("sigtstp");
        controller.handleContinueSignal();

        assertEquals(List.of(
            "terminal:suspend", "process:stop", "terminal:resume"), events);
    }

    @Test
    void registersOnlyUserRequestedSuspendSignal() {
        assertEquals(List.of("TSTP"), ReplExitController.jobControlSignals());
    }

    private static final class Fixture {
        final List<String> shutdowns = new ArrayList<>();
        final AtomicInteger stops = new AtomicInteger();
        final ReplExitController controller;

        Fixture(FakeActions actions, AtomicLong clock) {
            controller = new ReplExitController(
                (reason, code) -> shutdowns.add(reason + ":" + code),
                actions,
                () -> null,
                null,
                _ -> {},
                _ -> {},
                stops::incrementAndGet,
                _ -> {},
                clock::get);
        }
    }

    private static final class FakeActions implements ReplExitController.InterruptActions {
        boolean bashRunning;
        boolean turnRunning;
        boolean inputPresent;
        int bashInterrupts;
        int turnInterrupts;
        int softInterrupts;
        int inputClears;
        final List<String> hints = new ArrayList<>();

        @Override public boolean interruptBashIfRunning() {
            if (!bashRunning) return false;
            bashInterrupts++;
            return true;
        }

        @Override public boolean interruptTurnIfRunning() {
            if (!turnRunning) return false;
            turnInterrupts++;
            return true;
        }

        @Override public void softInterruptTurnIfRunning() {
            if (turnRunning) softInterrupts++;
        }

        @Override public boolean clearInputIfPresent() {
            if (!inputPresent) return false;
            inputPresent = false;
            inputClears++;
            return true;
        }

        @Override public void showExitHint(String text, int durationMs) {
            hints.add(text);
            assertEquals(ReplExitController.DOUBLE_PRESS_TIMEOUT_MS, durationMs);
        }
    }
}
