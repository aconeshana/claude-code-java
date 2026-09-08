package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The lane contract: producers never block, a slow client that falls behind
 * the bounded queue is disconnected, and write failures close the lane. This
 * mirrors the guarantee {@code SessionLinkOutboundWriter} gives the IM link —
 * the hub calls observers under its own lock, so offer() must stay lock-free.
 */
class SseConnectionTest {

    @Test
    @Timeout(10)
    void writesQueuedFramesInOrderToTheStream() throws Exception {
        RecordingOutput output = new RecordingOutput();
        AtomicBoolean closed = new AtomicBoolean();
        SseConnection connection = SseConnection.start(
            output, 16, Duration.ofSeconds(30), () -> closed.set(true));

        connection.offer(SseFrameWriter.event("a", "1", "one"));
        connection.offer(SseFrameWriter.event("b", "2", "two"));
        String expected = "event: a\nid: 1\ndata: one\n\nevent: b\nid: 2\ndata: two\n\n";
        awaitCondition(() ->
            expected.equals(new String(output.bytes(), StandardCharsets.UTF_8)));
        connection.close();
        assertThat(closed.get()).isTrue();
    }

    @Test
    @Timeout(10)
    void fullQueueDisconnectsTheLaggingClientWithoutBlockingTheProducer() throws Exception {
        // An output that never drains: the writer thread stays stuck on the
        // first frame while the queue fills up.
        BlockedOutput output = new BlockedOutput();
        List<Object> closeListeners = new CopyOnWriteArrayList<>();
        SseConnection connection = SseConnection.start(
            output, 2, Duration.ofSeconds(30), () -> closeListeners.add(new Object()));

        long startNanos = System.nanoTime();
        // Keep offering past capacity: every offer must return promptly.
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (connection.offer(SseFrameWriter.event("e", null, "x"))) {
                accepted++;
            }
            assertThat(System.nanoTime() - startNanos)
                .as("offer must not block on a stuck writer")
                .isLessThan(Duration.ofSeconds(2).toNanos());
        }
        // Capacity 2 accepts at most 2 queued frames plus one already dequeued
        // by the stuck writer; the overflow disconnect then fails every offer.
        assertThat(accepted).isLessThanOrEqualTo(3);
        awaitCondition(connection::isClosed);
        connection.close();
    }

    @Test
    @Timeout(10)
    void writeFailureClosesTheLane() throws Exception {
        FailingOutput output = new FailingOutput();
        AtomicBoolean closed = new AtomicBoolean();
        SseConnection connection = SseConnection.start(
            output, 8, Duration.ofSeconds(30), () -> closed.set(true));

        connection.offer(SseFrameWriter.event("e", null, "x"));

        // The onClose callback runs last inside close(); waiting on the flag
        // (not just isClosed) covers the window where close() has flipped its
        // state but has not yet reached the listener.
        awaitCondition(closed::get);
        assertThat(connection.isClosed()).isTrue();
    }

    /** Polls with jitter-free short sleeps until the condition holds or 5s pass. */
    private static void awaitCondition(BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 seconds");
            }
            Thread.sleep(20);
        }
    }

    private static final class RecordingOutput extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        byte[] bytes() {
            synchronized (buffer) {
                return buffer.toByteArray();
            }
        }

        @Override public void write(int b) {
            synchronized (buffer) {
                buffer.write(b);
            }
        }
    }

    private static final class BlockedOutput extends OutputStream {
        @Override public void write(int b) throws IOException {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        @Override public void flush() throws IOException {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    private static final class FailingOutput extends FilterOutputStream {
        private FailingOutput() {
            super(null);
        }

        @Override public void write(int b) throws IOException {
            throw new IOException("client gone");
        }
    }
}
