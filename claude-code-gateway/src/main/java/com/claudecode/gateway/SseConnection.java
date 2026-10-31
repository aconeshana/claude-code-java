package com.claudecode.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-writer, bounded outbound lane for one gateway SSE connection.
 *
 * <p>The session event hub invokes observers synchronously under its own lock;
 * an SSE socket write must therefore never run inline on the event thread or
 * one slow browser tab freezes the whole turn pipeline and the TUI. This class
 * applies the same lane shape as
 * {@code SessionLinkOutboundWriter}: producers only {@link #offer}, a dedicated
 * writer thread drains, and a full queue disconnects the lagging client.
 *
 * <p>The queue carries a sentinel-free idle wait: {@link #waitIdle} parks the
 * writer with a deadline so a heartbeat frame is emitted whenever the stream
 * has been quiet for the keep-alive interval. Write failures close the lane
 * and surface through {@code onClose} — for SSE, a failed write means the
 * client is gone.
 *
 * <p>{@link #close()} drops the lane at once; {@link #finish()} ends it
 * gracefully — frames already queued reach the client, then the response body
 * is closed so the HTTP connection returns to the client's keep-alive pool.
 */
public final class SseConnection implements AutoCloseable {

    private static final byte[] CLOSE = new byte[0];

    private final OutputStream output;
    private final Runnable onClose;
    private final ArrayBlockingQueue<byte[]> queue;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition frameAvailable = lock.newCondition();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean finishing = new AtomicBoolean();
    private final Duration keepAlive;

    private SseConnection(OutputStream output, int capacity, Duration keepAlive, Runnable onClose) {
        this.output = Objects.requireNonNull(output, "output");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
        this.onClose = onClose != null ? onClose : () -> {};
    }

    /** Wraps a live response body stream; the writer thread starts immediately. */
    public static SseConnection start(
            OutputStream output, int capacity, Duration keepAlive, Runnable onClose) {
        SseConnection connection =
            new SseConnection(output, capacity, keepAlive, onClose);
        Thread.ofVirtual().name("gateway-sse-writer").start(connection::writeLoop);
        return connection;
    }

    /**
     * Queues one frame; returns false — and disconnects this client — when the
     * bounded queue is full or the lane is already closed. Never blocks.
     */
    public boolean offer(byte[] frame) {
        Objects.requireNonNull(frame, "frame");
        if (closed.get() || finishing.get() || !queue.offer(frame)) {
            close();
            return false;
        }
        signalFrame();
        return true;
    }

    /**
     * Ends the lane after every frame queued so far has been written. Later
     * offers are refused. A queue too full to take the end marker means the
     * client is lagging anyway, so the lane is dropped as {@link #offer} would.
     */
    public void finish() {
        if (closed.get() || !finishing.compareAndSet(false, true)) return;
        if (!queue.offer(CLOSE)) {
            close();
            return;
        }
        signalFrame();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        lock.lock();
        try {
            frameAvailable.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            output.close();
        } catch (IOException | RuntimeException _) {
            // The client is already gone; closing is best-effort and must not
            // escape — a broken underlying stream can throw either kind.
        }
        try {
            onClose.run();
        } catch (RuntimeException _) {
            // Connection teardown must not be blocked by a listener failure.
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                byte[] frame = awaitFrame();
                if (frame == CLOSE) break;
                if (frame != null) {
                    output.write(frame);
                }
                output.flush();
            }
        } catch (IOException _) {
            // A write failure means the client disconnected; drop the lane.
        } finally {
            close();
        }
    }

    /**
     * Waits for the next frame, emitting a keep-alive heartbeat when the
     * keep-alive interval passes without traffic. Returns the {@link #CLOSE}
     * marker itself (queued by {@link #finish()}, or on interruption) so the
     * write loop can end; {@code null} only when the lane closed meanwhile.
     */
    private byte[] awaitFrame() throws IOException {
        byte[] frame = queue.poll();
        if (frame != null) return frame;
        long deadlineNanos = System.nanoTime() + keepAlive.toNanos();
        lock.lock();
        try {
            while (frame == null && !closed.get()) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    output.write(SseFrameWriter.heartbeat(null));
                    output.flush();
                    deadlineNanos = System.nanoTime() + keepAlive.toNanos();
                    continue;
                }
                try {
                    frameAvailable.await(remaining, TimeUnit.NANOSECONDS) ;
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return CLOSE;
                }
                frame = queue.poll();
            }
            return frame;
        } finally {
            lock.unlock();
        }
    }

    private void signalFrame() {
        lock.lock();
        try {
            frameAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
