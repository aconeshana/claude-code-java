package com.claudecode.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.claudecode.core.serialization.JsonUtils;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiStreamDiagnosticsTest {

    private static final MediaType JSON = MediaType.get("application/json");

    @Test
    void requestSummaryContainsWireShapeButNoCredentialsOrPromptContent() {
        CreateMessageRequest message = CreateMessageRequest.builder()
            .model("deepseek-v4-flash")
            .maxTokens(32_000)
            .systemPrompt("TOP SECRET SYSTEM PROMPT")
            .messages(List.of(new CreateMessageRequest.RequestMessage(
                "user", "TOP SECRET USER MESSAGE")))
            .tools(List.of(new CreateMessageRequest.ToolDefinition(
                "Bash", "TOP SECRET TOOL DESCRIPTION",
                JsonUtils.parseTree("{\"type\":\"object\"}"))))
            .thinking(CreateMessageRequest.ThinkingConfig.adaptive())
            .outputConfig(new CreateMessageRequest.OutputConfig("xhigh"))
            .contextManagement(new CreateMessageRequest.ContextManagementConfig(List.of()))
            .promptCachingEnabled(true)
            .stream(true)
            .build();
        String body = JsonUtils.toJson(message);
        Request request = new Request.Builder()
            .url("http://alice:password@deepgate.example/api/v1/messages?token=secret")
            .header("Authorization", "Bearer super-secret-token")
            .addHeader("anthropic-beta", "effort-2025-11-24")
            .addHeader("anthropic-beta", "interleaved-thinking-2025-05-14")
            .post(RequestBody.create(body, JSON))
            .build();

        AtomicLong nanos = new AtomicLong();
        ApiStreamDiagnostics.Trace trace = ApiStreamDiagnostics.create(
            request, message, body.length(), true, false, false, nanos::get);
        String summary = trace.requestSummary();

        assertTrue(contains(summary, "endpoint=http://deepgate.example/api/v1/messages"));
        assertTrue(contains(summary, "model=deepseek-v4-flash"));
        assertTrue(contains(summary, "max_tokens=32000"));
        assertTrue(contains(summary, "messages=1"));
        assertTrue(contains(summary, "tools=1"));
        assertTrue(contains(summary, "thinking=adaptive"));
        assertTrue(contains(summary, "effort=xhigh"));
        assertTrue(contains(summary, "auth=bearer"));
        assertTrue(contains(summary,
            "beta=effort-2025-11-24,interleaved-thinking-2025-05-14"));
        assertFalse(contains(summary, "alice"));
        assertFalse(contains(summary, "password"));
        assertFalse(contains(summary, "token=secret"));
        assertFalse(contains(summary, "super-secret-token"));
        assertFalse(contains(summary, "TOP SECRET"));
    }

    @Test
    void lifecycleSummaryDistinguishesNetworkArrivalFromConsumerDelivery() {
        AtomicLong nanos = new AtomicLong();
        CreateMessageRequest message = CreateMessageRequest.builder()
            .model("deepseek-v4-flash")
            .maxTokens(64)
            .messages(List.of())
            .stream(true)
            .build();
        Request request = new Request.Builder()
            .url("http://deepgate.example/api/v1/messages")
            .post(RequestBody.create("{}", JSON))
            .build();
        ApiStreamDiagnostics.Trace trace = ApiStreamDiagnostics.create(
            request, message, 2, false, false, true, nanos::get);

        trace.submitted();
        nanos.set(100_000_000L);
        String headers = trace.responseHeaders(200, "text/event-stream", "req-123", "route-a");
        nanos.set(250_000_000L);
        String firstSse = trace.sseEvent("message_start", 128);
        nanos.set(400_000_000L);
        String firstDelivery = trace.delivered("MessageStart");
        nanos.set(900_000_000L);
        trace.sseEvent("message_stop", 31);
        String complete = trace.completed("message_stop");

        assertTrue(contains(headers, "headers_ms=100"));
        assertTrue(contains(headers, "request_id=req-123"));
        assertTrue(contains(headers, "route=route-a"));
        assertTrue(contains(firstSse, "first_sse_ms=250"));
        assertTrue(contains(firstSse, "type=message_start"));
        assertTrue(contains(firstDelivery, "first_delivery_ms=400"));
        assertTrue(contains(firstDelivery, "event=MessageStart"));
        assertTrue(contains(complete, "total_ms=900"));
        assertTrue(contains(complete, "sse_events=2"));
        assertTrue(contains(complete, "sse_bytes=159"));
        assertTrue(contains(complete, "max_sse_gap_ms=650"));
        assertTrue(contains(complete, "reason=message_stop"));
    }

    @Test
    void failureSummaryIdentifiesWhetherHeadersOrEventsWereReached() {
        AtomicLong nanos = new AtomicLong(1);
        CreateMessageRequest message = CreateMessageRequest.builder()
            .model("deepseek-v4-flash")
            .maxTokens(64)
            .messages(List.of())
            .stream(true)
            .build();
        Request request = new Request.Builder()
            .url("http://deepgate.example/api/v1/messages")
            .post(RequestBody.create("{}", JSON))
            .build();

        ApiStreamDiagnostics.Trace beforeHeaders = ApiStreamDiagnostics.create(
            request, message, 2, false, false, false, nanos::get);
        beforeHeaders.submitted();
        nanos.set(20_000_001L);
        String connectionFailure = beforeHeaders.failed(new ApiException("secret detail", 0));

        ApiStreamDiagnostics.Trace beforeSse = ApiStreamDiagnostics.create(
            request, message, 2, false, false, false, nanos::get);
        beforeSse.submitted();
        beforeSse.responseHeaders(200, "text/event-stream", null, null);
        nanos.set(40_000_001L);
        String streamFailure = beforeSse.failed(new ApiException("secret detail", 0));

        assertNotNull(connectionFailure);
        assertTrue(contains(connectionFailure, "reason=before-headers"));
        assertTrue(contains(streamFailure, "reason=before-first-sse"));
        assertFalse(contains(connectionFailure, "secret detail"));
        assertFalse(contains(streamFailure, "secret detail"));
    }

    @Test
    void failureLogRetainsExceptionAndCause() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiStreamDiagnostics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ApiException failure = new ApiException(
            "DO_NOT_LOG_PROVIDER_DETAIL", 0, new IOException("DO_NOT_LOG_NETWORK_DETAIL"));
        try {
            ApiStreamDiagnostics.logFailure(
                "[api-diagnostic abc123] failed reason=before-headers", failure);

            ILoggingEvent event = appender.list.getLast();
            assertTrue(contains(event.getFormattedMessage(), "api-diagnostic abc123"));
            assertTrue(contains(event.getFormattedMessage(),
                "failureType=" + ApiException.class.getName()));
            assertNotNull(event.getThrowableProxy());
            assertEquals(ApiException.class.getName(), event.getThrowableProxy().getMessage());
            assertNotNull(event.getThrowableProxy().getCause());
            assertEquals(IOException.class.getName(),
                event.getThrowableProxy().getCause().getMessage());
            assertFalse(contains(event.getThrowableProxy().getMessage(),
                "DO_NOT_LOG_PROVIDER_DETAIL"));
            assertFalse(contains(event.getThrowableProxy().getCause().getMessage(),
                "DO_NOT_LOG_NETWORK_DETAIL"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static boolean contains(String value, String fragment) {
        return Strings.CS.contains(value, fragment);
    }
}
