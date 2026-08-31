package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.googlecode.lanterna.gui2.AbstractTextGUIThread;
import com.googlecode.lanterna.gui2.AsynchronousTextGUIThread;
import com.googlecode.lanterna.gui2.TextGUI;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.TextGUIThreadFactory;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.io.EOFException;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Defers Lanterna's GUI loop until the complete REPL scene is ready, while accepting {@code
 * invokeLater} work during component construction.
 */
final class PrestartTextGUIThreadFactory implements TextGUIThreadFactory {

    @Explanation("Prioritizes the dedicated Java input/render loop over optional startup and "
        + "service workers; 197 owns input and paint on its single Bun event-loop thread")
    static final int GUI_THREAD_PRIORITY = Math.min(Thread.MAX_PRIORITY,
        Thread.NORM_PRIORITY + 1);

    /**
     * Interactive terminal input must not inherit Lanterna's idle 16 ms frame throttle.
     */
    private static final long IDLE_FRAME_GAP_MS = 4L;
    private static final long ACTIVE_INPUT_WINDOW_NS = TimeUnit.MILLISECONDS.toNanos(150L);
    private static final long ACTIVE_IDLE_PARK_NS = TimeUnit.MICROSECONDS.toNanos(25L);

    private DeferredThread created;

    @Override
    public TextGUIThread createTextGUIThread(TextGUI gui) {
        if (created != null) throw new IllegalStateException("GUI thread already created");
        created = new DeferredThread(gui);
        return created;
    }

    void start() {
        if (created == null) throw new IllegalStateException("GUI thread not created");
        created.start();
    }

    record Cycle(boolean didWork, boolean processedInput) {}

    /**
     * {@link com.googlecode.lanterna.gui2.AbstractTextGUI#processInput} already
     * drains every currently available key before returning. Call it exactly
     * once: a second call only performs an empty terminal poll and delays paint.
     */
    static Cycle processInputBatchAndUpdate(TextGUI gui, Queue<Runnable> tasks)
            throws IOException {
        boolean processedInput = gui.processInput();

        boolean updated = gui.isPendingUpdate();
        if (processedInput && updated) {

            gui.updateScreen();
        }

        boolean processedTask = false;
        Runnable task;
        while ((task = tasks.poll()) != null) {
            processedTask = true;
            task.run();
        }

        boolean updateAfterTasks = gui.isPendingUpdate();
        if (updateAfterTasks) gui.updateScreen();
        return new Cycle(processedInput || processedTask || updated || updateAfterTasks,
            processedInput);
    }

    /**
     * Unlike Lanterna's {@code SeparateTextGUIThread}, this loop deliberately
     * skips the eager {@code updateScreen} before queued construction tasks.
     * The first cycle drains those tasks and then emits one complete frame.
     */
    private static final class DeferredThread extends AbstractTextGUIThread
            implements AsynchronousTextGUIThread {
        /** How often {@link #invokeAndWait} re-checks whether the loop still drains tasks. */
        private static final long STOPPED_LOOP_RECHECK_MS = 50L;

        private final Thread thread;
        private final CountDownLatch stopped = new CountDownLatch(1);
        private volatile State state = State.CREATED;

        private DeferredThread(TextGUI gui) {
            super(gui);
            thread = Thread.ofPlatform()
                .name("LanternaGUI")
                .priority(GUI_THREAD_PRIORITY)
                .unstarted(this::mainLoop);
        }

        @Override
        public synchronized void start() {
            if (state != State.CREATED) return;
            state = State.STARTED;
            thread.start();
        }

        @Override
        public void stop() {
            if (state == State.STARTED) state = State.STOPPING;
        }

        @Override
        public void waitForStop() throws InterruptedException {
            stopped.await();
        }

        @Override
        public void waitForStop(long timeout, TimeUnit unit) throws InterruptedException {
            if (stopped.await(timeout, unit)) return;
        }

        @Override
        public State getState() {
            return state;
        }

        @Override
        public Thread getThread() {
            return thread;
        }

        /**
         * {@inheritDoc}
         *
         * <p>{@link AbstractTextGUIThread#invokeAndWait} waits on an unbounded
         * latch, which never counts down if the loop is not draining
         * {@code customTasks} — before {@link #start} and after {@link #stop}
         * the queue is inert. Callers that marshal UI work from a worker thread
         * would then block forever, so run inline in those windows and re-check
         * the state while waiting. The {@code claimed} CAS guarantees exactly one
         * execution when the loop stops after the task was already queued.
         */
        @Override
        public void invokeAndWait(Runnable runnable) throws InterruptedException {
            if (state != State.STARTED || Thread.currentThread() == thread) {
                runnable.run();
                return;
            }
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean claimed = new AtomicBoolean();
            invokeLater(() -> {
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    runnable.run();
                } finally {
                    done.countDown();
                }
            });
            while (!done.await(STOPPED_LOOP_RECHECK_MS, TimeUnit.MILLISECONDS)) {
                if (state == State.STARTED || !claimed.compareAndSet(false, true)) continue;
                try {
                    runnable.run();
                } finally {
                    done.countDown();
                }
            }
        }

        private void mainLoop() {
            long previousFrameAt = 0L;
            long responsiveUntil = 0L;
            try {
                while (state == State.STARTED) {
                    try {
                        Cycle cycle = processInputBatchAndUpdate(textGUI, customTasks);
                        if (cycle.processedInput()) {
                            responsiveUntil = System.nanoTime() + ACTIVE_INPUT_WINDOW_NS;
                        }
                        if (!cycle.didWork()) {
                            if (System.nanoTime() < responsiveUntil) {
                                LockSupport.parkNanos(ACTIVE_IDLE_PARK_NS);
                                if (Thread.interrupted()) throw new InterruptedException();
                            } else {
                                // Intentional idle backoff for Lanterna's polling GUI loop.
                                //noinspection BusyWait
                                Thread.sleep(1L);
                            }
                            continue;
                        }
                        long now = System.currentTimeMillis();
                        if (!cycle.processedInput()) {
                            // Intentional frame pacing after non-input GUI work.
                            //noinspection BusyWait
                            Thread.sleep(Math.max(0L, IDLE_FRAME_GAP_MS - (now - previousFrameAt)));
                        }
                        previousFrameAt = System.currentTimeMillis();
                    } catch (EOFException _) {
                        stop();
                        closeWindows();
                    } catch (IOException io) {
                        if (exceptionHandler.onIOException(io)) stop();
                    } catch (RuntimeException runtime) {
                        if (exceptionHandler.onRuntimeException(runtime)) {
                            TuiOutputGuard.recordFatalThreadFailure(
                                Thread.currentThread(), runtime);
                            stop();
                        }
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                        stop();
                    }
                }
            } catch (Error fatal) {
                TuiOutputGuard.recordFatalThreadFailure(Thread.currentThread(), fatal);
                throw fatal;
            } finally {
                state = State.STOPPED;
                stopped.countDown();
            }
        }

        private void closeWindows() {
            if (textGUI instanceof WindowBasedTextGUI windowGui) {
                for (Window window : windowGui.getWindows()) window.close();
            }
        }
    }
}
