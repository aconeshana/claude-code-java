package com.claudecode.runtime.sessionlink;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SessionLinkOutboundWriterTest {

    @Test
    void preservesFrameOrderWithOneSocketWriter() throws Exception {
        RecordingChannel channel = new RecordingChannel();
        try (SessionLinkOutboundWriter writer = new SessionLinkOutboundWriter(
                channel, new SessionLinkCodec(), 8, _ -> {})) {
            writer.start();
            assertTrue(writer.offer(event("first")));
            writer.sendAndWait(event("second"), Duration.ofSeconds(2));
        }

        String wire = channel.content();
        assertTrue(wire.indexOf("\"name\":\"first\"")
            < wire.indexOf("\"name\":\"second\""), wire);
    }

    @Test
    void saturatedRemoteNeverBlocksSemanticPublisher() throws Exception {
        BlockingChannel channel = new BlockingChannel();
        try (SessionLinkOutboundWriter writer = new SessionLinkOutboundWriter(
                channel, new SessionLinkCodec(), 1, _ -> {})) {
            writer.start();
            assertTrue(writer.offer(event("in-flight")));
            assertTrue(channel.writeStarted.await(2, TimeUnit.SECONDS));
            assertTrue(writer.offer(event("queued")));

            long started = System.nanoTime();
            assertFalse(writer.offer(event("overflow")));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 100,
                "semantic publisher waited " + elapsedMillis + "ms for a slow remote");
        } finally {
            channel.release.countDown();
        }
    }

    private static SessionLinkFrame event(String name) {
        return SessionLinkFrame.event(
            name, "session-1", JsonUtils.getMapper().createObjectNode());
    }

    private static final class RecordingChannel implements WritableByteChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean open = true;

        @Override public int write(ByteBuffer source) {
            int count = source.remaining();
            byte[] copy = new byte[count];
            source.get(copy);
            bytes.writeBytes(copy);
            return count;
        }

        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        private String content() { return bytes.toString(StandardCharsets.UTF_8); }
    }

    private static final class BlockingChannel implements WritableByteChannel {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean open = true;

        @Override public int write(ByteBuffer source) throws IOException {
            writeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            if (!open) throw new IOException("closed");
            int count = source.remaining();
            source.position(source.limit());
            return count;
        }

        @Override public boolean isOpen() { return open; }
        @Override public void close() {
            open = false;
            release.countDown();
        }
    }
}
