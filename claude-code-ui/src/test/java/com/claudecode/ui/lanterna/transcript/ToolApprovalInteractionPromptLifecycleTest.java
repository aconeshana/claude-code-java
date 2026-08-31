package com.claudecode.ui.lanterna.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.ui.lanterna.dialog.PermissionDialog;
import com.claudecode.ui.lanterna.dialog.PermissionPreviewPreparer;
import com.claudecode.ui.lanterna.components.SpinnerComponent;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.claudecode.ui.lanterna.input.InputPanel;


class ToolApprovalInteractionPromptLifecycleTest {

    @Test
    void permissionClockPausesEvenWhenStreamingTextAlreadyHidTheSpinner() {
        SpinnerComponent spinner = new SpinnerComponent();
        spinner.start("Running");
        spinner.setVisible(false);
        long wallStart = System.currentTimeMillis();

        ToolApprovalInteraction.withTurnClockPaused(spinner, true, () -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return null;
        });

        long wallElapsed = System.currentTimeMillis() - wallStart;
        long activeElapsed = spinner.adjustedElapsedMsForTranscript();
        assertTrue(activeElapsed + 60 < wallElapsed,
            "dialog wait must be excluded independently of spinner visibility");
        spinner.stop();
    }

    @Test
    void permissionPromptCollapsesInputAndEmitsWaitingBeforeRestoringItOnce() {
        InputPanel input = new InputPanel();
        List<SDKMessage.StreamEvent> events = new ArrayList<>();
        AtomicBoolean focusRestored = new AtomicBoolean();
        var lifecycle = new ToolApprovalInteraction.PermissionPromptUiLifecycle(
            input, events::add, "Bash", () -> focusRestored.set(true));

        lifecycle.begin();

        assertEquals(new TerminalSize(0, 0), input.calculatePreferredSize());
        assertEquals(List.of("permission_waiting:Bash"), eventNames(events));

        lifecycle.close();
        lifecycle.close();

        assertTrue(input.calculatePreferredSize().getRows() > 0);
        assertTrue(focusRestored.get());
        assertEquals(List.of("permission_waiting:Bash", "permission_resolved:Bash"),
            eventNames(events), "close must be idempotent when dialog completion races cleanup");
    }

    @Test
    void permissionPromptQueueSerializesConcurrentRequestsWithoutDroppingEitherResult()
            throws Exception {
        var queue = new ToolApprovalInteraction.PermissionPromptQueue();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> queue.execute(() -> {
                recordActivePrompt(active, maxActive);
                firstEntered.countDown();
                await(releaseFirst);
                active.decrementAndGet();
                return "first";
            }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

            var second = executor.submit(() -> queue.execute(() -> {
                recordActivePrompt(active, maxActive);
                secondEntered.countDown();
                active.decrementAndGet();
                return "second";
            }));

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS),
                "the second prompt must stay queued while the first owns the dialog");
            releaseFirst.countDown();

            assertEquals("first", first.get(1, TimeUnit.SECONDS));
            assertEquals("second", second.get(1, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get(), "only one request may own the dialog callback");
        }
    }

    @Test
    void firstCallerReturnsBeforeAQueuedSecondPromptIsAnswered() throws Exception {
        var queue = new ToolApprovalInteraction.PermissionPromptQueue();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> queue.execute(() -> {
                firstEntered.countDown();
                await(releaseFirst);
                return "first";
            }));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            var second = executor.submit(() -> queue.execute(() -> {
                secondEntered.countDown();
                await(releaseSecond);
                return "second";
            }));

            releaseFirst.countDown();
            assertEquals("first", first.get(1, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            releaseSecond.countDown();
            assertEquals("second", second.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void resolvingQueuedRequestDoesNotCancelCurrentlyDisplayedRequest() throws Exception {
        var queue = new ToolApprovalInteraction.PermissionPromptQueue();
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var firstCancelled = new AtomicBoolean();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> queue.execute("request-a", () -> {
                firstEntered.countDown();
                await(releaseFirst);
                return "first";
            }, () -> firstCancelled.set(true)));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            var second = executor.submit(() -> queue.execute("request-b", () -> {
                secondEntered.countDown();
                return "second";
            }, () -> {}));

            queue.cancel("request-b");
            assertFalse(firstCancelled.get());
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();

            assertEquals("first", first.get(1, TimeUnit.SECONDS));
            assertNull(second.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void remoteAutoApprovalBeforePermissionMountDoesNotLeaveStaleDialog() throws Exception {
        var terminal = new DefaultVirtualTerminal(new TerminalSize(100, 40));
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        var gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        var dialog = new PermissionDialog();
        var queue = new ToolApprovalInteraction.PermissionPromptQueue();
        var requestStarted = new CountDownLatch(1);
        var allowDialogMount = new CountDownLatch(1);
        var result = new CompletableFuture<PermissionAskCallback.Result>();

        Thread.ofVirtual().start(() -> result.complete(queue.execute("request-a", () -> {
            requestStarted.countDown();
            await(allowDialogMount);
            return dialog.showAndWait(gui,
                PermissionPreviewPreparer.standard().prepare(
                    PermissionAskContext.simple("Read", null, "tool-1")), null,
                _ -> {}, () -> {}, _ -> {},
                () -> queue.isCancelled("request-a"));
        }, () -> gui.getGUIThread().invokeLater(dialog::cancelPending))));

        assertTrue(requestStarted.await(1, TimeUnit.SECONDS));
        queue.cancel("request-a");
        gui.getGUIThread().processEventsAndUpdate();
        assertFalse(dialog.isActive(), "the early close runs while no dialog is mounted");

        allowDialogMount.countDown();
        long deadline = System.currentTimeMillis() + 2000;
        while (!result.isDone() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }

        assertFalse(result.get(2, TimeUnit.SECONDS).allowed());
        assertFalse(dialog.isActive(),
            "a remotely resolved request must not mount a stale local permission dialog");
    }


    private static void recordActivePrompt(AtomicInteger active, AtomicInteger maxActive) {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> eventNames(List<SDKMessage.StreamEvent> events) {
        return events.stream()
            .map(event -> event.eventType() + ":" + event.data())
            .toList();
    }
}
