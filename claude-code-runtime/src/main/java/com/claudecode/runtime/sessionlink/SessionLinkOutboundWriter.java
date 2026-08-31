package com.claudecode.runtime.sessionlink;

import com.claudecode.core.annotation.Explanation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single-writer, bounded outbound lane for one Session Link connection.
 */
@Explanation("Non-blocking semantic fan-out to a bounded Session Link writer")
final class SessionLinkOutboundWriter implements AutoCloseable {

    private record Pending(SessionLinkFrame frame, CompletableFuture<Void> completion) {}

    private final WritableByteChannel channel;
    private final SessionLinkCodec codec;
    private final ArrayBlockingQueue<Pending> queue;
    private final Consumer<IOException> failureHandler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread worker;

    SessionLinkOutboundWriter(
            WritableByteChannel channel,
            SessionLinkCodec codec,
            int capacity,
            Consumer<IOException> failureHandler) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.failureHandler = failureHandler != null ? failureHandler : _ -> {};
    }

    void start() {
        if (!started.compareAndSet(false, true)) return;
        if (closed.get()) throw new IllegalStateException("outbound writer is closed");
        worker = Thread.ofVirtual().name("session-link-writer").start(this::writeLoop);
    }

    /** Returns immediately; false means the remote connection is not keeping up. */
    boolean offer(SessionLinkFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return !closed.get() && queue.offer(new Pending(frame, null));
    }

    void sendAndWait(SessionLinkFrame frame, Duration timeout) throws IOException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(timeout, "timeout");
        if (closed.get()) throw new IOException("Session Link connection is closed");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        long timeoutNanos = timeout.toNanos();
        long startedAt = System.nanoTime();
        try {
            if (!queue.offer(new Pending(frame, completion), timeoutNanos, TimeUnit.NANOSECONDS)) {
                throw new IOException("Session Link outbound queue is full");
            }
            long remaining = timeoutNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) throw new TimeoutException("Session Link write timed out");
            completion.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Session Link write interrupted", interrupted);
        } catch (TimeoutException timeoutFailure) {
            throw new IOException("Session Link write timed out", timeoutFailure);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Session Link write failed", cause);
        }
    }

    private void writeLoop() {
        Pending current = null;
        try {
            while (!closed.get()) {
                current = queue.take();
                write(current.frame());
                complete(current, null);
                current = null;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            complete(current, failure);
            try {
                failureHandler.accept(failure);
            } catch (RuntimeException _) {
                // The transport is already failed; reporting must not strand waiters.
            }
        } finally {
            IOException failure = new IOException("Session Link connection is closed");
            complete(current, failure);
            Pending pending;
            while ((pending = queue.poll()) != null) complete(pending, failure);
        }
    }

    private void write(SessionLinkFrame frame) throws IOException {
        byte[] encoded = codec.encode(frame);
        ByteBuffer buffer = ByteBuffer.allocate(encoded.length + 1);
        buffer.put(encoded).put((byte) '\n').flip();
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void complete(Pending pending, IOException failure) {
        if (pending == null || pending.completion() == null) return;
        if (failure == null) pending.completion().complete(null);
        else pending.completion().completeExceptionally(failure);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            channel.close();
        } catch (IOException _) {
        }
        Thread snapshot = worker;
        if (snapshot != null) snapshot.interrupt();
    }
}
