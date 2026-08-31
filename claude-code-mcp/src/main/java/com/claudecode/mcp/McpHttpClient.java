package com.claudecode.mcp;

import com.claudecode.http.SharedHttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared OkHttp profiles for MCP network transports.
 *
 * <ul>
 *   <li>supplies a
 *       fresh bounded deadline for each Streamable HTTP request.</li>
 *   <li>shares connection pooling,
 *       dispatch, and fast fallback across remote MCP servers; closes POST
 *       responses whose bodies are intentionally discarded.</li>
 *   <li>leaves
 *       response bodies open for method-specific long tool deadlines.</li>
 * </ul>
 */
public final class McpHttpClient {

    private static final OkHttpClient REQUEST_RESPONSE = SharedHttpClient.shared().newBuilder()
        .connectTimeout(McpTimeouts.responseHeadersTimeout())
        .readTimeout(Duration.ZERO)
        .writeTimeout(McpTimeouts.responseHeadersTimeout())
        .callTimeout(Duration.ZERO)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build();
    private static final OkHttpClient EVENT_STREAM = SharedHttpClient.shared().newBuilder()
        .connectTimeout(McpTimeouts.responseHeadersTimeout())
        .callTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(McpTimeouts.responseHeadersTimeout())
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(false)
        .build();

    private McpHttpClient() {}

    public static OkHttpClient requestResponse() {
        return REQUEST_RESPONSE;
    }

    public static OkHttpClient eventStream() {
        return EVENT_STREAM;
    }

    /** Executes a fire-and-forget POST and immediately releases its response body. */
    static void executeAndClose(OkHttpClient client, Request request) throws IOException {
        executeForHeaders(client, request, McpTimeouts.responseHeadersTimeout()).close();
    }

    /**
     * Bounds only the wait for response headers. The response body remains
     * available to the caller for a method-specific long-running operation.
     */
    static Response executeForHeaders(OkHttpClient client, Request request,
                                               Duration timeout) throws IOException {
        Call call = client.newCall(request);
        FutureTask<Response> task = new FutureTask<>(call::execute);
        Thread.ofVirtual().name("mcp-http-headers").start(task);
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel();
            InterruptedIOException timeoutError = new InterruptedIOException(
                "MCP response headers timed out after " + timeout.toMillis() + "ms");
            timeoutError.initCause(e);
            throw timeoutError;
        } catch (InterruptedException e) {
            call.cancel();
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                "Interrupted waiting for MCP response headers");
            interrupted.initCause(e);
            throw interrupted;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("MCP request failed", cause);
        }
    }
}
