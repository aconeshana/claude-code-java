package com.claudecode.api;

import org.apache.commons.lang3.Strings;
import com.claudecode.http.CancellationRegistrar;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSourceStreamBridgeTest {

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
    void optInWatchdogTerminatesAnIdleOpenStream() throws Exception {
        startIdleStream();
        Iterator<StreamEvent> events = EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> { },
            Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(true, Duration.ofMillis(120)),
            CancellationRegistrar.NONE);

        StreamEvent.Error error = assertInstanceOf(StreamEvent.Error.class, events.next());
        assertTrue(Strings.CS.contains(error.exception().getMessage(), "idle timeout"));
        assertFalse(events.hasNext());
    }

    @Test
    void externalCancellationCancelsAnOpenStream() throws Exception {
        startIdleStream();
        AtomicReference<Runnable> cancel = new AtomicReference<>();
        CancellationRegistrar registrar = action -> {
            cancel.set(action);
            return () -> { };
        };
        Iterator<StreamEvent> events = EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> { },
            Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
            registrar);

        cancel.get().run();

        StreamEvent.Error error = assertInstanceOf(StreamEvent.Error.class, events.next());
        assertTrue(Strings.CS.contains(error.exception().getMessage(), "aborted"));
        assertFalse(events.hasNext());
    }

    @Test
    void connectionCloseWithoutMessageStopIsAnIncompleteStreamError() throws Exception {
        startIdleStream();
        Iterator<StreamEvent> events = EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> { },
            Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
            CancellationRegistrar.NONE);

        releaseStream.countDown();

        StreamEvent.Error error = assertInstanceOf(StreamEvent.Error.class, events.next());
        assertTrue(Strings.CS.contains(error.exception().getMessage(), "message_stop"));
        assertFalse(events.hasNext());
    }

    @Test
    void preservesProviderApiExceptionFromValidErrorEvent() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            byte[] body = "event: error\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Iterator<StreamEvent> events = EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> {
                throw new ApiException("server_error: request failed", 0, "server_error");
            }, Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
            CancellationRegistrar.NONE);

        StreamEvent.Error error = assertInstanceOf(StreamEvent.Error.class, events.next());
        assertEquals("server_error: request failed", error.exception().getMessage());
        assertEquals("server_error", error.exception().errorType());
    }

    @Test
    void submissionCallbackFiresBeforeDelayedResponseHeaders() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        CountDownLatch releaseHeaders = new CountDownLatch(1);
        releaseStream = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            requestSeen.countDown();
            try {
                releaseHeaders.await(5, TimeUnit.SECONDS);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().flush();
                releaseStream.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();

        CountDownLatch submitted = new CountDownLatch(1);
        CompletableFuture<Iterator<StreamEvent>> connection = CompletableFuture.supplyAsync(() ->
            EventSourceStreamBridge.connect(
                streamingClient(), request(), (_, _, _) -> { },
                Duration.ofSeconds(2),
                new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
                CancellationRegistrar.NONE,
                submitted::countDown),
            executor);

        assertTrue(submitted.await(1, TimeUnit.SECONDS));
        assertTrue(requestSeen.await(1, TimeUnit.SECONDS));
        assertFalse(connection.isDone(), "connect must still be waiting for response headers");
        releaseHeaders.countDown();
        Iterator<StreamEvent> events = connection.get(2, TimeUnit.SECONDS);
        releaseStream.countDown();
        assertInstanceOf(StreamEvent.Error.class, events.next());
    }

    @Test
    void nonSuccessfulResponseCarriesAFriendlyMessageForKnownErrorPatterns() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            byte[] body = ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"`tool_use` ids must be unique\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        // A non-200 response arrives before the SSE handshake completes, so
// connect fails synchronously (see class Javadoc: "connect (succeed
        // or throw)") rather than handing back an iterator that later yields
        // a StreamEvent.Error.
        ApiException failure = assertThrows(ApiException.class, () -> EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> { },
            Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
            CancellationRegistrar.NONE));

        assertTrue(Strings.CS.contains(failure.friendlyMessage(), "duplicate tool_use ID"));
        assertTrue(Strings.CS.contains(failure.friendlyMessage(), "/rewind"));
        assertFalse(Strings.CS.contains(failure.friendlyMessage(), "invalid_request_error"),
            "friendly message must not embed the raw error body");
    }

    @Test
    void streamingPromptTooLongResponseIsClassifiedLikeTheNonStreamingPath() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            byte[] body = ("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                + "\"message\":\"Prompt is too long\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        ApiException failure = assertThrows(ApiException.class, () -> EventSourceStreamBridge.connect(
            streamingClient(), request(), (_, _, _) -> { },
            Duration.ofSeconds(2),
            new ApiTimeouts.StreamWatchdog(false, Duration.ofSeconds(90)),
            CancellationRegistrar.NONE));

        assertInstanceOf(PromptTooLongException.class, failure);
        assertEquals("Prompt is too long", failure.getMessage());
    }

    private void startIdleStream() throws Exception {
        releaseStream = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try {
                releaseStream.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    private OkHttpClient streamingClient() {
        return new OkHttpClient.Builder()
            .readTimeout(Duration.ZERO)
            .build();
    }

    private Request request() {
        return new Request.Builder()
            .url("http://127.0.0.1:" + server.getAddress().getPort() + "/events")
            .build();
    }
}
