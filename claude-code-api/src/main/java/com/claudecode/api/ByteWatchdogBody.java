package com.claudecode.api;

import okhttp3.MediaType;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Byte-level idle watchdog over a streaming response body.
 *
 * <p>The SSE bridge arms its idle watchdog per <em>event</em>: a server that
 * keeps the transport busy without ever completing an event (or a socket that
 * silently stops delivering bytes) leaves the connection wedged indefinitely.
 * This wrapper re-arms a timer on every delivered chunk instead, and aborts the
 * body once no byte has arrived within the resolved window — at which point the
 * reader observes an {@link ApiStreamException} carrying {@link
 * ApiStreamException.Reason#WATCHDOG}, so the streaming adapter's existing
 * watchdog retry and non-streaming fallback paths engage unchanged.
 *
 * <p>A firing that lands far later than its deadline means the timer thread was
 * starved rather than the peer stalling — in practice the machine slept — so
 * that case is classified as a stale connection, which retries on a fresh
 * socket instead of finalizing the partial response.
 *
 * <ul>
 *   <li>the byte-level stream watchdog that wraps a
 *       streaming response body and aborts it once the resolved idle window
 *       elapses without a byte (including the sleep/suspend distinction).</li>
 * </ul>
 */
final class ByteWatchdogBody extends ResponseBody {

    private static final Logger log = LoggerFactory.getLogger(ByteWatchdogBody.class);
    private static final ScheduledExecutorService TIMER =
        Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("api-byte-watchdog").unstarted(r));

    private final ResponseBody delegate;
    private final long idleMillis;
    private final BufferedSource source;
    private final AtomicReference<ApiStreamException> aborted = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> timer = new AtomicReference<>();
    /** Monotonic time of the most recent delivered byte, the watchdog's deadline base. */
    private volatile long lastByteNanos = System.nanoTime();
    private volatile boolean bodyClosed;

    private ByteWatchdogBody(ResponseBody delegate, Duration idleTimeout) {
        this.delegate = delegate;
        this.idleMillis = idleTimeout.toMillis();
        this.source = Okio.buffer(new ForwardingSource(delegate.source()) {
            @Override
            public long read(@NotNull Buffer sink, long byteCount) throws IOException {
                long read;
                try {
                    read = super.read(sink, byteCount);
                } catch (IOException error) {
                    throw abortIfArmed(error);
                }
                if (read > 0) rearm();
                throwIfAborted();
                return read;
            }

            @Override
            public void close() throws IOException {
                stop();
                super.close();
            }
        });
        rearm();
    }

    /**
     * Wraps {@code response}'s body when the byte watchdog applies, so the SSE
     * reader pulls through the watchdog source instead of the raw one.
     */
    static Response wrap(Response response, ApiTimeouts.ByteWatchdog watchdog) {
        if (!watchdog.enabled() || response == null || response.body() == null) {
            return response;
        }
        return response.newBuilder()
            .body(new ByteWatchdogBody(response.body(), watchdog.idleTimeout()))
            .build();
    }

    /**
     * A firing that lands at least half its window past the deadline means the
     * timer thread was starved, not that the peer stalled.
     */
    static boolean isSuspendFiring(long lateMillis, long idleMillis) {
        return lateMillis >= idleMillis / 2;
    }

    private void rearm() {
        if (bodyClosed) return;
        lastByteNanos = System.nanoTime();
        long delay = idleMillis;
        ScheduledFuture<?> previous = timer.getAndSet(
            TIMER.schedule(this::onIdleElapsed, delay, TimeUnit.MILLISECONDS));
        cancel(previous);
    }

    private void onIdleElapsed() {
        if (bodyClosed) return;
        long late = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastByteNanos) - idleMillis;
        boolean suspended = isSuspendFiring(late, idleMillis);
        ApiStreamException failure = suspended
            ? new ApiStreamException(
                "Stream watchdog detected system suspend; aborting to retry on a fresh connection",
                0, ApiStreamException.Reason.STALE_CONNECTION)
            : new ApiStreamException(
                "stream idle: no bytes for " + idleMillis + "ms",
                0, ApiStreamException.Reason.WATCHDOG);
        log.warn("[byte-watchdog] firing: idle={}ms late={}ms suspended={}",
            idleMillis, late, suspended);
        if (!aborted.compareAndSet(null, failure)) return;
        // Unblock a reader parked in read(); a reader that is not parked observes
        // the latch on its next read, before any further byte is forwarded.
        try {
            delegate.close();
        } catch (Exception _) {
            // Closing the wedged body is best-effort; the abort is already latched.
        }
    }

    private void stop() {
        bodyClosed = true;
        cancel(timer.getAndSet(null));
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) future.cancel(false);
    }

    /** Surfaces the latched abort in place of whatever the interrupted socket read reported. */
    private void throwIfAborted() throws IOException {
        ApiStreamException failure = aborted.get();
        if (failure != null) throw new IOException(failure.getMessage(), failure);
    }

    /**
     * Closing the body to wake a parked reader surfaces as a socket-level failure
     * ({@code SocketException: Socket closed}, or a {@code SocketTimeoutException}
     * when OkHttp's socket timeout races the close), so the latch wins over
     * whatever the interrupted read reported. User-initiated abort never reaches
     * here: the bridge latches its own termination before the socket is closed.
     */
    private IOException abortIfArmed(IOException error) {
        ApiStreamException failure = aborted.get();
        if (failure == null) return error;
        // Closing the body to wake a parked reader surfaces as a socket-level
        // failure, so the socket error is attached to the latched abort rather
        // than replacing it — the abort is what callers must classify on.
        failure.addSuppressed(error);
        return new IOException(failure.getMessage(), failure);
    }

    @Override
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() {
        return delegate.contentLength();
    }

    @Override
    public @NotNull BufferedSource source() {
        return source;
    }

    @Override
    public void close() {
        stop();
        delegate.close();
    }
}
