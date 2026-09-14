package com.claudecode.api;

import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.Strings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ByteWatchdogBodyTest {

    private HttpServer server;
    private ExecutorService executor;
    private CountDownLatch releaseStream;

    @AfterEach
    void stopServer() {
        if (releaseStream != null) releaseStream.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void disabledWatchdogReturnsTheOriginalResponse() throws Exception {
        startChunkThenIdle("data: one\n\n");
        try (Response response = open()) {
            assertSame(response, ByteWatchdogBody.wrap(response,
                new ApiTimeouts.ByteWatchdog(false, Duration.ZERO)));
        }
    }

    @Test
    void abortsAnIdleBodyEvenThoughTheSocketStaysOpen() throws Exception {
        startChunkThenIdle("data: first\n\n");
        try (Response response = open();
             Response watched = ByteWatchdogBody.wrap(response,
                 new ApiTimeouts.ByteWatchdog(true, Duration.ofMillis(200)))) {
            BufferedSource source = watched.body().source();

            // The chunk arrives before the window elapses, so this also proves the
            // frame is forwarded normally and then re-arms the timer.
            assertEquals("data: first", source.readUtf8Line());

            // Bytes already buffered stay deliverable — the abort lands on the
            // first read that has to ask the underlying (now-closed) body again.
            ApiStreamException abort = assertInstanceOfStreamAbort(
                assertThrows(IOException.class, () -> {
                    for (int i = 0; i < 4; i++) source.readUtf8Line();
                }));
            assertTrue(Strings.CS.contains(abort.getMessage(), "no bytes for 200ms"));
            assertEquals(ApiStreamException.Reason.WATCHDOG, abort.reason());
        }
    }

    @Test
    void rearmKeepsAHealthyBodyAlivePastTheWindow() throws Exception {
        startDripFeed();
        try (Response response = open();
             Response watched = ByteWatchdogBody.wrap(response,
                 new ApiTimeouts.ByteWatchdog(true, Duration.ofMillis(150)))) {
            BufferedSource source = watched.body().source();
            for (int i = 0; i < 5; i++) {
                assertEquals("data: chunk" + i, source.readUtf8Line());
                // Consume the blank separator line so the next read has to wait for
                // the server rather than being served from the local buffer.
                assertEquals("", source.readUtf8Line());
            }
        }
    }

    @Test
    void aFiringWellPastItsDeadlineIsTreatedAsASuspend() {
        // A timer cannot fire before its deadline, so a large `late` means the timer
        // thread itself was starved — the process slept rather than the peer
        // stalling — and the caller should retry on a fresh connection instead.
        assertTrue(ByteWatchdogBody.isSuspendFiring(100, 200));
        assertTrue(ByteWatchdogBody.isSuspendFiring(5000, 200));
        assertFalse(ByteWatchdogBody.isSuspendFiring(1, 200));
        assertFalse(ByteWatchdogBody.isSuspendFiring(99, 200));
    }

    @Test
    void aBodyThatEndsNaturallyIsNotAborted() throws Exception {
        startChunkThenIdle("data: only\n\n");
        try (Response response = open();
             Response watched = ByteWatchdogBody.wrap(response,
                 new ApiTimeouts.ByteWatchdog(true, Duration.ofMillis(80)))) {
            BufferedSource source = watched.body().source();
            assertEquals("data: only", source.readUtf8Line());
            assertEquals("", source.readUtf8Line());
            releaseStream.countDown();
            // A clean end of stream must read as EOF, not as a watchdog abort.
            assertNull(source.readUtf8Line());
        }
    }

    private static ApiStreamException assertInstanceOfStreamAbort(IOException failure) {
        assertNotNull(failure.getCause(), "the abort must be attached as the cause");
        return (ApiStreamException) failure.getCause();
    }

    private Response open() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(Duration.ZERO)
            .build();
        return client.newCall(new Request.Builder()
            .url("http://127.0.0.1:" + server.getAddress().getPort() + "/events")
            .build()).execute();
    }

    /** Sends one SSE frame, flushes it, then holds the connection open and silent. */
    private void startChunkThenIdle(String chunk) throws Exception {
        releaseStream = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(chunk.getBytes(StandardCharsets.UTF_8));
                body.flush();
                releaseStream.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    /** Emits a frame every 60ms for long enough to outlive several watchdog windows. */
    private void startDripFeed() throws Exception {
        releaseStream = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (int i = 0; i < 5; i++) {
                    body.write(("data: chunk" + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    body.flush();
                    Thread.sleep(60);
                }
                releaseStream.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }
}
