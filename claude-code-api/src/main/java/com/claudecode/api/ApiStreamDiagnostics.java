package com.claudecode.api;

import com.claudecode.core.error.ErrorUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * PII-free request lifecycle diagnostics for streaming API calls.
 * The trace deliberately records only wire shape, timings, event names, and
 * byte counts; it never records credentials, prompts, tool schemas/arguments,
 * or SSE payloads. Detailed lifecycle checkpoints reuse
 * {@code ANTHROPIC_DEBUG=1} / {@code -Danthropic.debug=true}. The safe request
 * and response-header milestones remain at INFO, and terminal failures at
 * WARN, so first-pass diagnosis needs no flag.
 *
 * <ul>
 *   <li>request-sent, response-header,
 *       first-chunk, streaming-stall, and stream-completion checkpoints.</li>
 * </ul>
 */
final class ApiStreamDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(ApiStreamDiagnostics.class);
    private static final long STALL_NANOS = 30_000_000_000L;

    private ApiStreamDiagnostics() {}

    static Request attach(Request request, CreateMessageRequest message, String body,
                          boolean bearerAuth, boolean apiKeyAuth,
                          ApiTimeouts.StreamWatchdog watchdog) {
        Trace trace = new Trace(UUID.randomUUID().toString().substring(0, 8), request, message,
            body.getBytes(StandardCharsets.UTF_8).length,
            bearerAuth, apiKeyAuth, watchdog.enabled(), System::nanoTime, debugEnabled());
        return request.newBuilder().tag(Trace.class, trace).build();
    }

    static Trace from(Request request) {
        return request.tag(Trace.class);
    }

    static void logDetailed(Trace trace, String message) {
        if (trace != null && trace.detailed() && message != null) log.info(message);
    }

    static void logBaseline(String message) {
        if (message != null) log.info(message);
    }

    static void logFailure(String message, Throwable failure) {
        if (message == null) return;
        if (failure == null) {
            log.warn(message);
            return;
        }
        log.warn("{} failureType={}", message, failure.getClass().getName(),
            ErrorUtils.redactedForLogging(failure));
    }

    static Trace create(Request request, CreateMessageRequest message, int bodyBytes,
                        boolean bearerAuth, boolean apiKeyAuth, boolean watchdogEnabled,
                        LongSupplier nanoTime) {
        return new Trace(UUID.randomUUID().toString().substring(0, 8), request, message,
            bodyBytes, bearerAuth, apiKeyAuth, watchdogEnabled, nanoTime, true);
    }

    static boolean debugEnabled() {
        if (Boolean.getBoolean("anthropic.debug")) return true;
        String value = SubprocessEnvironment.get("ANTHROPIC_DEBUG");
        return Strings.CI.equals("true", value) || Strings.CS.equals("1", value);
    }

    static final class Trace {
        private final String id;
        private final String requestContext;
        private final String requestSummary;
        private final LongSupplier nanoTime;
        private final boolean detailed;
        private final AtomicLong sseEvents = new AtomicLong();
        private final AtomicLong sseBytes = new AtomicLong();
        private final AtomicBoolean headersSeen = new AtomicBoolean();
        private final AtomicBoolean firstSseSeen = new AtomicBoolean();
        private final AtomicBoolean firstDeliverySeen = new AtomicBoolean();
        private final AtomicBoolean submitted = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile long submittedNanos;
        private volatile long lastSseNanos;
        private volatile long maxSseGapNanos;

        private Trace(String id, Request request, CreateMessageRequest message, int bodyBytes,
                      boolean bearerAuth, boolean apiKeyAuth, boolean watchdogEnabled,
                      LongSupplier nanoTime, boolean detailed) {
            this.id = id;
            this.nanoTime = nanoTime;
            this.detailed = detailed;
            this.requestContext = "endpoint=" + safeEndpoint(request)
                + " model=" + safeValue(message.model())
                + " stream=" + message.stream()
                + " max_tokens=" + message.maxTokens()
                + " messages=" + size(message.messages())
                + " tools=" + size(message.tools())
                + " body_bytes=" + bodyBytes
                + " thinking=" + safeValue(message.thinking() == null
                    ? null : message.thinking().type())
                + " effort=" + safeValue(effectiveEffort(message))
                + " context_management=" + (message.contextManagement() != null)
                + " prompt_cache=" + message.promptCachingEnabled()
                + " auth=" + authMode(bearerAuth, apiKeyAuth)
                + " beta=" + safeHeaderValues(request, "anthropic-beta")
                + " watchdog=" + (watchdogEnabled ? "enabled" : "disabled");
            this.requestSummary = "[api-diagnostic " + id + "] submit " + requestContext;
        }

        String requestSummary() {
            return requestSummary;
        }

        boolean detailed() {
            return detailed;
        }

        void submitted() {
            submittedNanos = nanoTime.getAsLong();
            submitted.set(true);
        }

        String responseHeaders(Response response) {
            String route = firstHeader(response,
                "x-deepgate-route", "x-deepgate-route-id", "x-route-name", "x-model-route");
            String requestId = firstHeader(response,
                "request-id", "x-request-id", "x-claude-request-id");
            return responseHeaders(response.code(), response.header("Content-Type"), requestId, route)
                + " content_encoding=" + safeValue(response.header("Content-Encoding"))
                + " transfer_encoding=" + safeValue(response.header("Transfer-Encoding"))
                + " server=" + safeValue(response.header("Server"));
        }

        String responseHeaders(int status, String contentType, String requestId, String route) {
            headersSeen.set(true);
            return prefix("headers")
                + " headers_ms=" + elapsedMillis()
                + " status=" + status
                + " content_type=" + safeValue(contentType)
                + " request_id=" + safeValue(requestId)
                + " route=" + safeValue(route);
        }

        synchronized String sseEvent(String type, int dataBytes) {
            long now = nanoTime.getAsLong();
            long previous = lastSseNanos;
            lastSseNanos = now;
            sseEvents.incrementAndGet();
            sseBytes.addAndGet(Math.max(dataBytes, 0));
            if (previous != 0L) {
                maxSseGapNanos = Math.max(maxSseGapNanos, now - previous);
            }
            if (firstSseSeen.compareAndSet(false, true)) {
                return prefix("first-sse")
                    + " first_sse_ms=" + elapsedMillis(now)
                    + " type=" + safeValue(type)
                    + " data_bytes=" + Math.max(dataBytes, 0);
            }
            long gap = previous == 0L ? 0L : now - previous;
            if (gap >= STALL_NANOS) {
                return prefix("stall")
                    + " gap_ms=" + nanosToMillis(gap)
                    + " next_type=" + safeValue(type);
            }
            return null;
        }

        String delivered(String eventType) {
            if (!firstDeliverySeen.compareAndSet(false, true)) return null;
            return prefix("first-delivery")
                + " first_delivery_ms=" + elapsedMillis()
                + " event=" + safeValue(eventType);
        }

        String completed(String reason) {
            if (!finished.compareAndSet(false, true)) return null;
            return terminal("complete", reason, null);
        }

        String failed(ApiException failure) {
            if (!finished.compareAndSet(false, true)) return null;
            int status = failure == null ? 0 : failure.statusCode();
            String phase;
            if (!submitted.get()) phase = "before-submit";
            else if (status > 0 && !headersSeen.get()) phase = "http-response-error";
            else if (!headersSeen.get()) phase = "before-headers";
            else if (!firstSseSeen.get()) phase = "before-first-sse";
            else phase = "mid-stream";
            String failureType = failure == null ? "unknown" : failure.getClass().getSimpleName();
            String causeType = rootCauseType(failure);
            return terminal("failure", phase, failureType)
                + " status=" + status
                + " cause_type=" + causeType
                + " " + requestContext;
        }

        private String terminal(String outcome, String reason, String failureType) {
            return prefix(outcome)
                + " total_ms=" + elapsedMillis()
                + " sse_events=" + sseEvents.get()
                + " sse_bytes=" + sseBytes.get()
                + " max_sse_gap_ms=" + nanosToMillis(maxSseGapNanos)
                + " reason=" + safeValue(reason)
                + (failureType == null ? "" : " failure_type=" + safeValue(failureType));
        }

        private String prefix(String stage) {
            return "[api-diagnostic " + id + "] " + stage;
        }

        private long elapsedMillis() {
            return elapsedMillis(nanoTime.getAsLong());
        }

        private long elapsedMillis(long now) {
            return !submitted.get() ? 0L : nanosToMillis(now - submittedNanos);
        }
    }

    static String safeEndpoint(Request request) {
        var url = request.url();
        String port = url.port() == HttpUrl.defaultPort(url.scheme()) ? "" : ":" + url.port();
        return url.scheme() + "://" + url.host() + port + url.encodedPath();
    }

    private static String safeHeaderValues(Request request, String name) {
        List<String> values = request.headers(name);
        return values.isEmpty() ? "none" : safeValue(String.join(",", values));
    }

    private static String firstHeader(Response response, String... names) {
        for (String name : names) {
            String value = response.header(name);
            if (StringUtils.isNotBlank(value)) return value;
        }
        return null;
    }

    private static String effectiveEffort(CreateMessageRequest message) {
        if (StringUtils.isNotBlank(message.effort())) return message.effort();
        return message.outputConfig() == null ? null : message.outputConfig().effort();
    }

    private static String authMode(boolean bearer, boolean apiKey) {
        if (bearer && apiKey) return "bearer+api-key";
        if (bearer) return "bearer";
        if (apiKey) return "api-key";
        return "none";
    }

    private static int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static String safeValue(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) return "none";
        return String.valueOf(value).replaceAll("[\\s\\p{Cntrl}]+", "_");
    }

    private static String rootCauseType(Throwable failure) {
        if (failure == null) return "none";
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return safeValue(current.getClass().getSimpleName());
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }
}
