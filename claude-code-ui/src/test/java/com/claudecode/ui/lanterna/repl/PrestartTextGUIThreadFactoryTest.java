package com.claudecode.ui.lanterna.repl;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.AsynchronousTextGUIThread;
import com.googlecode.lanterna.gui2.TextGUI;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.graphics.Theme;
import com.googlecode.lanterna.screen.Screen;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regression coverage for PTY input batching before a terminal frame is committed. */
class PrestartTextGUIThreadFactoryTest {

    @Test
    void guiLoopHasInteractivePriority() {
        PrestartTextGUIThreadFactory factory = new PrestartTextGUIThreadFactory();
        TextGUIThread guiThread = factory.createTextGUIThread(new FakeTextGUI(0));

        assertEquals(PrestartTextGUIThreadFactory.GUI_THREAD_PRIORITY,
            guiThread.getThread().getPriority());
    }

    @Test
    void delegatesOnePtyDrainAndCommitsOneFrame() throws IOException {
        FakeTextGUI gui = new FakeTextGUI(32);
        Queue<Runnable> tasks = new ArrayDeque<>();

        PrestartTextGUIThreadFactory.Cycle cycle =
            PrestartTextGUIThreadFactory.processInputBatchAndUpdate(gui, tasks);

        assertTrue(cycle.processedInput());
        assertEquals(1, gui.processedInputs,
            "AbstractTextGUI.processInput owns the complete terminal drain internally");
        assertEquals(1, gui.screenUpdates,
            "one PTY byte burst should produce one terminal commit");
    }

    @Test
    void doesNotAddASecondEmptyTerminalPoll() throws IOException {
        FakeTextGUI gui = new FakeTextGUI(200);

        PrestartTextGUIThreadFactory.processInputBatchAndUpdate(
            gui, new ArrayDeque<>());

        assertEquals(1, gui.processedInputs);
        assertEquals(1, gui.screenUpdates);
    }

    @Test
    void inputFrameCommitsBeforeDeferredQueryTasks() throws IOException {
        FakeTextGUI gui = new FakeTextGUI(1);
        Queue<Runnable> tasks = new ArrayDeque<>();
        tasks.add(() -> gui.taskObservedScreenUpdates = gui.screenUpdates);

        PrestartTextGUIThreadFactory.processInputBatchAndUpdate(gui, tasks);

        assertEquals(1, gui.taskObservedScreenUpdates,
            "typeahead/query work should run only after the visible input frame commits");
    }

    @Test
    void recordsFatalGuiErrorsBeforeTheThreadStops() throws Exception {
        var diagnosticPath = TuiOutputGuard.diagnosticPath();
        Files.deleteIfExists(diagnosticPath);
        FakeTextGUI gui = new FakeTextGUI(1);
        gui.fatalInputError = new AssertionError("fatal-gui-test");
        PrestartTextGUIThreadFactory factory = new PrestartTextGUIThreadFactory();
        AsynchronousTextGUIThread guiThread = (AsynchronousTextGUIThread)
            factory.createTextGUIThread(gui);

        factory.start();
        guiThread.waitForStop(2, TimeUnit.SECONDS);

        String diagnostic = Files.readString(diagnosticPath);
        assertTrue(Strings.CS.contains(diagnostic, "fatal-gui-test"));
        assertTrue(Strings.CS.contains(diagnostic, "LanternaGUI"));
    }

    // ── invokeAndWait: UI work marshalled from slash-command virtual threads ──
    // Mutating a Lanterna component off the GUI thread deadlocks against a
// concurrent updateScreen (component monitor vs. parent-chain monitors),
    // so SessionController routes its UI half through invokeAndWait. These
    // guard the two windows where the loop cannot run the task for us.

    @Test
    void invokeAndWaitRunsInlineBeforeTheLoopStarts() throws Exception {
        PrestartTextGUIThreadFactory factory = new PrestartTextGUIThreadFactory();
        TextGUIThread guiThread = factory.createTextGUIThread(new FakeTextGUI(0));
        var ran = new AtomicReference<Thread>();

        guiThread.invokeAndWait(() -> ran.set(Thread.currentThread()));

        assertSame(Thread.currentThread(), ran.get(),
            "construction-time UI work must not wait for a loop that has not started");
    }

    @Test
    void invokeAndWaitMarshalsOntoTheGuiThread() throws Exception {
        PrestartTextGUIThreadFactory factory = new PrestartTextGUIThreadFactory();
        AsynchronousTextGUIThread guiThread =
            (AsynchronousTextGUIThread) factory.createTextGUIThread(new FakeTextGUI(0));
        factory.start();
        var ran = new AtomicReference<Thread>();

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                guiThread.invokeAndWait(() -> ran.set(Thread.currentThread())));
        } finally {
            guiThread.stop();
            guiThread.waitForStop(2, TimeUnit.SECONDS);
        }

        assertEquals("LanternaGUI", ran.get().getName());
    }

    @Test
    void invokeAndWaitDoesNotHangOnceTheLoopStopped() throws Exception {
        PrestartTextGUIThreadFactory factory = new PrestartTextGUIThreadFactory();
        AsynchronousTextGUIThread guiThread =
            (AsynchronousTextGUIThread) factory.createTextGUIThread(new FakeTextGUI(0));
        factory.start();
        guiThread.stop();
        guiThread.waitForStop(2, TimeUnit.SECONDS);
        var runs = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            guiThread.invokeAndWait(runs::incrementAndGet),
            "a stopped loop never drains its queue — the caller must not block forever");

        assertEquals(1, runs.get(), "the task still runs, and runs exactly once");
    }

    private static final class FakeTextGUI implements TextGUI {
        private int remainingInputs;
        private int processedInputs;
        private int screenUpdates;
        private boolean pendingUpdate;
        private int taskObservedScreenUpdates = -1;
        private Error fatalInputError;

        private FakeTextGUI(int remainingInputs) {
            this.remainingInputs = remainingInputs;
        }

        @Override public boolean processInput() {
            if (fatalInputError != null) throw fatalInputError;
            if (remainingInputs == 0) return false;
            remainingInputs--;
            processedInputs++;
            pendingUpdate = true;
            return true;
        }

        @Override public void updateScreen() { screenUpdates++; pendingUpdate = false; }
        @Override public boolean isPendingUpdate() { return pendingUpdate; }
        @Override public Theme getTheme() { return null; }
        @Override public void setTheme(Theme theme) {}
        @Override public Screen getScreen() { return null; }
        @Override public void setVirtualScreenEnabled(boolean enabled) {}
        @Override public TextGUIThread getGUIThread() { return null; }
        @Override public Interactable getFocusedInteractable() { return null; }
        @Override public void addListener(Listener listener) {}
        @Override public void removeListener(Listener listener) {}
    }
}
