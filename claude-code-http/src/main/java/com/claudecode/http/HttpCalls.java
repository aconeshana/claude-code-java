package com.claudecode.http;

import okhttp3.*;

import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes OkHttp calls with an operation-specific total deadline.
 *
 * <ul>
 *   <li>per-request fetch and domain
 *       preflight timeouts layered over a reusable HTTP transport.</li>
 *   <li>per-hook timeout layered over
 *       the shared HTTP client.</li>
 *   <li>bounded plugin-statistics
 *       fetch without constructing a new client per request.</li>
 *   <li>keeps an AbortSignal-linked
 *       cancellation callback active until the response body is closed.</li>
 * </ul>
 *
 * <p>The caller owns and must close the returned {@link Response}.</p>
 */
public final class HttpCalls {

    private HttpCalls() {}

    public static Response execute(OkHttpClient client, Request request, Duration timeout)
            throws IOException, InterruptedException {
        return execute(client, request, timeout, CancellationRegistrar.NONE);
    }

    public static Response execute(OkHttpClient client, Request request, Duration timeout,
                                   CancellationRegistrar cancellation)
            throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("request was interrupted");
        }
        Call call = client.newCall(request);
        call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        AutoCloseable registration = cancellation.register(call::cancel);
        try {
            return executeAndTransferOwnership(call, registration);
        } catch (InterruptedIOException e) {
            closeQuietly(registration);
            if (Thread.currentThread().isInterrupted()) {
                InterruptedException interrupted = new InterruptedException(e.getMessage());
                interrupted.initCause(e);
                throw interrupted;
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            closeQuietly(registration);
            throw e;
        }
    }

    /**
     * Transfers the raw response body's ownership to the returned response.
     * The original and rebuilt {@link Response} share that body, so closing the
     * original here would invalidate the value returned to the caller.
     */
    private static Response executeAndTransferOwnership(
            Call call, AutoCloseable registration) throws IOException {
        Response response = call.execute();
        try {
            return response.newBuilder()
                .body(new RegistrationResponseBody(response.body(), registration))
                .build();
        } catch (RuntimeException e) {
            response.close();
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception _) {
            // Removing a cancellation listener is best-effort cleanup.
        }
    }

    private static final class RegistrationResponseBody extends ResponseBody {
        private final ResponseBody delegate;
        private final AutoCloseable registration;
        private final AtomicBoolean released = new AtomicBoolean();
        private final BufferedSource source;

        RegistrationResponseBody(ResponseBody delegate, AutoCloseable registration) {
            this.delegate = delegate;
            this.registration = registration;
            this.source = Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override
                public long read(@NotNull Buffer sink, long byteCount) throws IOException {
                    long read = super.read(sink, byteCount);
                    if (read == -1) release();
                    return read;
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        release();
                    }
                }
            });
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

        private void release() {
            if (released.compareAndSet(false, true)) closeQuietly(registration);
        }
    }
}
