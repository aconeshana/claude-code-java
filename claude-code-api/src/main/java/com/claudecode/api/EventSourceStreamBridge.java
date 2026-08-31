package com.claudecode.api;

import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.message.ApiErrorFriendlyText;
import com.claudecode.http.CancellationRegistrar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Bridges {@code okhttp-sse}'s async {@link EventSourceListener} callbacks to
 * the synchronous {@code Iterator<StreamEvent>} contract
 * {@link LlmClient#createMessageStream} requires: connect (succeed or throw)
 * happens synchronously before returning, then events are pulled through a
 * {@link BlockingQueue}. matches the same "bridge an async/callback source to
 * a pull-based Iterator" pattern {@code claude-code-core}'s
 * {@code QueryLoop} already uses (Virtual Thread + BlockingQueue),
 * just fed by OkHttp's own dispatcher thread instead of a thread we spawn
 * ourselves.
 *
 * <p>Each client supplies its own event → {@code StreamEvent} translation via
 * {@code translator} — Anthropic's named-event SSE shape
 * ({@code message_start}/{@code content_block_delta}/...) maps one SSE event
 * to one {@code StreamEvent} 1:1, while OpenAI-compat's anonymous
 * {@code data:}-only chunks need stateful accumulation (a tool-call's
 * arguments stream across many chunks before becoming one
 * {@code ContentBlockStart}) that can emit zero, one, or several
 * {@code StreamEvent}s per chunk — hence a sink-style callback rather than a
 * 1:1 {@code Function}. This class only owns the connect-handshake +
 * event-delivery plumbing, never the parsing.
 *
 * <ul>
 *   <li>initial-fetch timeout,
 *       AbortSignal propagation, response-header metadata capture, and opt-in
 *       streaming idle watchdog.</li>
 * </ul>
 */
final class EventSourceStreamBridge {

    private static final Logger log = LoggerFactory.getLogger(EventSourceStreamBridge.class);
    private static final ScheduledExecutorService WATCHDOG =
        Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("api-stream-watchdog").unstarted(r));

    /** Queue sentinel signaling the stream ended (real event or connection close) — never surfaced as a StreamEvent. */
    private static final Object DONE = new Object();

    private EventSourceStreamBridge() {}

    /** Converts one SSE {@code (type, data)} pair to zero, one, or many {@link StreamEvent}s, pushed via {@code sink}. */
    @FunctionalInterface
    interface EventTranslator {
        void translate(String type, String data, Consumer<StreamEvent> sink);

        /** Gives a stateful translator access to response metadata before events arrive. */
        default void onOpen(Response response, Consumer<StreamEvent> sink) {}

        /**
         * Called when the connection closes normally without ever having
         * translated an explicit terminal event. No-op by default —
         * Anthropic's stream always emits a real {@code message_stop}.
         * Stateful accumulators (OpenAI-compat, where the server may close
         * the connection without an explicit {@code [DONE]}) override this
         * to flush trailing synthetic events; implementations must be
         * idempotent since this can fire after an explicit terminal event
         * too (the connection still closes right after).
         */
        default void onClosed(Consumer<StreamEvent> sink) {}
    }

    /**
     * Establishes the connection synchronously (blocks the calling thread
     * until the server accepts or rejects it), then returns an
     * {@code Iterator} that pulls subsequently received events. Throws
     * {@link ApiException} synchronously if the connection fails before ever
     * accepting the stream — callers never receive a "half-open" iterator,
     * matching the pre-OkHttp contract exactly.
     */
    static Iterator<StreamEvent> connect(OkHttpClient client, Request request,
                                         EventTranslator translator, Duration connectTimeout,
                                         ApiTimeouts.StreamWatchdog watchdog,
                                         CancellationRegistrar cancellation) {
        return connect(client, request, translator, connectTimeout, watchdog, cancellation, null);
    }

    /**
     * Establishes the same synchronous response-header handshake as the six-argument overload, while
     * exposing the earlier point at which OkHttp has already enqueued the request.
     */
    static Iterator<StreamEvent> connect(OkHttpClient client, Request request,
                                         EventTranslator translator, Duration connectTimeout,
                                         ApiTimeouts.StreamWatchdog watchdog,
                                         CancellationRegistrar cancellation,
                                         Runnable onRequestSubmitted) {
        ApiStreamDiagnostics.Trace diagnostics = ApiStreamDiagnostics.from(request);
        if (diagnostics != null) {
            diagnostics.submitted();
            ApiStreamDiagnostics.logBaseline(diagnostics.requestSummary());
        }
        BridgeState state = new BridgeState(translator, watchdog, diagnostics);
        EventSource eventSource = EventSources.createFactory(client)
            .newEventSource(request, state.listener());
        state.attach(eventSource, cancellation);
        if (onRequestSubmitted != null) onRequestSubmitted.run();
        try {
            state.awaitConnected(connectTimeout);
        } catch (ExecutionException e) {
            state.abort();
            Throwable cause = e.getCause();
            if (cause instanceof ApiException apiException) throw apiException;
            throw new ApiException("Failed to send request: " + cause.getMessage(), 0, cause);
        } catch (TimeoutException e) {
            state.fail(new ApiException(
                "API streaming connection timed out after " + connectTimeout.toMillis() + "ms", 0, e));
            throw new ApiException(
                "API streaming connection timed out after " + connectTimeout.toMillis() + "ms", 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.abort();
            throw new ApiException("Request interrupted", 0, e);
        }

        return new QueueIterator(state.queue, diagnostics);
    }

    private static final class BridgeState {
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> connected = new CompletableFuture<>();
        private final EventTranslator translator;
        private final ApiTimeouts.StreamWatchdog watchdog;
        private final ApiStreamDiagnostics.Trace diagnostics;
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<EventSource> eventSource = new AtomicReference<>();
        private final AtomicReference<AutoCloseable> registration = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> warningTask = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        private final AtomicBoolean messageStopSeen = new AtomicBoolean();
        private final AtomicReference<StreamEvent.RequestTiming> pendingTiming =
            new AtomicReference<>();
        private final Consumer<StreamEvent> sink = this::acceptTranslated;

        private BridgeState(EventTranslator translator, ApiTimeouts.StreamWatchdog watchdog,
                            ApiStreamDiagnostics.Trace diagnostics) {
            this.translator = translator;
            this.watchdog = watchdog;
            this.diagnostics = diagnostics;
        }

        private EventSourceListener listener() {
            return new EventSourceListener() {
                @Override
                public void onOpen(@NotNull EventSource source, @NotNull Response response) {
                    if (terminated.get()) return;
                    if (diagnostics != null) {
                        ApiStreamDiagnostics.logBaseline(diagnostics.responseHeaders(response));
                    }
                    ApiRequestTiming timing = response.request().tag(ApiRequestTiming.class);
                    if (timing != null && timing.lastAttemptStartMs() > 0L) {
                        pendingTiming.set(new StreamEvent.RequestTiming(
                            timing.lastAttemptStartMs()));
                    }
                    try {
                        translator.onOpen(response, sink);
                    } catch (Exception error) {
                        fail(new ApiException(
                            "Failed to read streaming response metadata: "
                                + error.getMessage(), 0, error));
                        return;
                    }
                    connected.complete(null);
                    resetWatchdog();
                }

                @Override
                public void onEvent(@NotNull EventSource source, String id,
                                    @NotNull String type, @NotNull String data) {
                    if (terminated.get()) return;
                    resetWatchdog();
                    if (diagnostics != null) {
                        ApiStreamDiagnostics.logDetailed(diagnostics, diagnostics.sseEvent(
                            type, data.getBytes(StandardCharsets.UTF_8).length));
                    }
                    try {
                        translator.translate(type, data, sink);
                    } catch (ApiException apiFailure) {
                        // A translator may use ApiException to surface a valid
                        // provider error event (for example OpenAI Responses
                        // response.failed/error). Preserve its classification
                        // and message; this is not an SSE parsing failure.
                        accept(new StreamEvent.Error(apiFailure));
                    } catch (Exception e) {
                        log.warn("Failed to parse SSE event [type={}, failureType={}]",
                            type, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
                        accept(new StreamEvent.Error(
                            new ApiException("Failed to parse event: " + e.getMessage(), 0, e)));
                    }
                }

                @Override
                public void onClosed(@NotNull EventSource source) {
                    if (terminated.get()) return;
                    try {
                        translator.onClosed(sink);
                    } catch (Exception e) {
                        log.warn("Translator onClosed failed [failureType={}]",
                            e.getClass().getName(), ErrorUtils.redactedForLogging(e));
                    }
                    completeFromConnectionClose();
                }

                @Override
                public void onFailure(@NotNull EventSource source, Throwable t, Response response) {
                    if (terminated.get()) {
                        if (response != null) response.close();
                        return;
                    }
                    fail(toApiException(t, response));
                }
            };
        }

        private void acceptTranslated(StreamEvent event) {
            accept(event);
            StreamEvent.RequestTiming timing = pendingTiming.getAndSet(null);
            if (timing != null) accept(timing);
        }

        private void attach(EventSource source, CancellationRegistrar cancellation) {
            eventSource.set(source);
            AutoCloseable handle = cancellation.register(() ->
                fail(new ApiStreamException("Request aborted", 0,
                    ApiStreamException.Reason.ABORTED)));
            registration.set(handle);
            if (terminated.get()) closeRegistration();
        }

        private void awaitConnected(Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            connected.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void accept(StreamEvent event) {
            if (terminated.get()) return;
            if (event instanceof StreamEvent.MessageStop) {
                messageStopSeen.set(true);
            }
            queue.add(event);
            if (event instanceof StreamEvent.MessageStop) completeNormally();
        }

        private void completeNormally() {
            if (!terminated.compareAndSet(false, true)) return;
            if (diagnostics != null) {
                ApiStreamDiagnostics.logDetailed(
                    diagnostics, diagnostics.completed("message_stop"));
            }
            cancelWatchdog();
            closeRegistration();
            if (!connected.isDone()) connected.completeExceptionally(
                new ApiException("Streaming connection closed before opening", 0));
            queue.add(DONE);
        }

        /**
         * A 200 response whose SSE connection closes before message_stop is not a successful empty turn.
         */
        private void completeFromConnectionClose() {
            if (messageStopSeen.get()) {
                completeNormally();
            } else {
                fail(new ApiStreamException(
                    "Streaming response ended before message_stop", 0,
                    ApiStreamException.Reason.STALE_CONNECTION));
            }
        }

        private void fail(ApiException failure) {
            if (!terminated.compareAndSet(false, true)) return;
            if (diagnostics != null) {
                ApiStreamDiagnostics.logFailure(diagnostics.failed(failure), failure);
            }
            cancelWatchdog();
            EventSource source = eventSource.get();
            if (source != null) source.cancel();
            closeRegistration();
            if (!connected.isDone()) {
                connected.completeExceptionally(failure);
            } else {
                queue.add(new StreamEvent.Error(failure));
                queue.add(DONE);
            }
        }

        private void abort() {
            fail(new ApiStreamException("Request aborted", 0,
                ApiStreamException.Reason.ABORTED));
        }

        private void resetWatchdog() {
            cancelWatchdog();
            if (!watchdog.enabled() || terminated.get()) return;
            warningTask.set(WATCHDOG.schedule(
                () -> log.warn("Streaming idle warning: no chunks received for {}ms",
                    watchdog.warningTimeout().toMillis()),
                watchdog.warningTimeout().toMillis(), TimeUnit.MILLISECONDS));
            timeoutTask.set(WATCHDOG.schedule(
                () -> fail(new ApiStreamException(
                    "Streaming idle timeout after " + watchdog.idleTimeout().toMillis() + "ms", 0,
                    ApiStreamException.Reason.WATCHDOG)),
                watchdog.idleTimeout().toMillis(), TimeUnit.MILLISECONDS));
        }

        private void cancelWatchdog() {
            cancel(warningTask.getAndSet(null));
            cancel(timeoutTask.getAndSet(null));
        }

        private static void cancel(ScheduledFuture<?> future) {
            if (future != null) future.cancel(false);
        }

        private void closeRegistration() {
            AutoCloseable handle = registration.getAndSet(null);
            if (handle == null) return;
            try {
                handle.close();
            } catch (Exception _) {
                // Cancellation-listener cleanup is best effort.
            }
        }
    }

    /**
     * matches the old {@code catch (IOException)}/non-200 handling: a present
     * {@code response} means the server actually replied (read its body for
     * the error message, same as the synchronous request path); a null
     * response with a {@code Throwable} means a connection-level failure.
     */
    private static ApiException toApiException(Throwable t, Response response) {
        if (response != null) {
            String body;
            try (Response r = response) {
                body = r.body().string();
            } catch (Exception _) {
                body = "";
            }
            Long retryAfterSeconds = parseRetryAfterSeconds(response.header("Retry-After"));
            String promptTooLongMessage = PromptTooLongException.extractFromResponseBody(body);
            if (promptTooLongMessage != null) {
                return new PromptTooLongException(
                    promptTooLongMessage, response.code(), null, retryAfterSeconds);
            }
            String friendlyMessage = ApiErrorFriendlyText.classify(response.code(), body);
            return new ApiException("API request failed: " + body, response.code(), null,
                retryAfterSeconds, friendlyMessage);
        }
        String message = t != null ? t.getMessage() : "Unknown streaming failure";
        String friendlyMessage = ApiErrorFriendlyText.connectionFriendlyMessage(message);
        return new ApiException("Failed to send request: " + message, 0, t, friendlyMessage);
    }

    private static Long parseRetryAfterSeconds(String headerValue) {
        if (StringUtils.isBlank(headerValue)) {
            return null;
        }
        try {
            return Long.parseLong(headerValue.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static final class QueueIterator implements Iterator<StreamEvent> {
        private final BlockingQueue<Object> queue;
        private final ApiStreamDiagnostics.Trace diagnostics;
        private StreamEvent nextEvent;
        private boolean done;

        QueueIterator(BlockingQueue<Object> queue, ApiStreamDiagnostics.Trace diagnostics) {
            this.queue = queue;
            this.diagnostics = diagnostics;
        }

        @Override
        public boolean hasNext() {
            if (nextEvent != null) return true;
            if (done) return false;
            Object item;
            try {
                item = queue.take();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                done = true;
                return false;
            }
            if (item == DONE) {
                done = true;
                return false;
            }
            nextEvent = (StreamEvent) item;
            if (diagnostics != null) {
                ApiStreamDiagnostics.logDetailed(diagnostics, diagnostics.delivered(
                    nextEvent.getClass().getSimpleName()));
            }
            return true;
        }

        @Override
        public StreamEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            StreamEvent event = nextEvent;
            nextEvent = null;
            return event;
        }
    }
}
