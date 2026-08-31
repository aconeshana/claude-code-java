package com.claudecode.api;

import okhttp3.Headers;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Direct unit coverage for {@link AnthropicSdkClient#apiExceptionFor}, the non-streaming
 *  counterpart to {@link EventSourceStreamBridgeTest}'s streaming-path assertions. */
class AnthropicSdkClientTest {

    @Test
    void apiExceptionFor_promptTooLong_returnsPromptTooLongException() {
        String body = "{\"error\":{\"message\":\"Prompt is too long\"}}";
        ApiException failure = AnthropicSdkClient.apiExceptionFor(400, body, Headers.of());
        assertInstanceOf(PromptTooLongException.class, failure);
        assertEquals("Prompt is too long", failure.getMessage());
    }

    @Test
    void apiExceptionFor_knownPattern_carriesAFriendlyMessageDistinctFromGetMessage() {
        String body = "{\"error\":{\"message\":\"`tool_use` ids must be unique\"}}";
        ApiException failure = AnthropicSdkClient.apiExceptionFor(400, body, Headers.of());
        assertTrue(Strings.CS.contains(failure.friendlyMessage(), "duplicate tool_use ID"));
// getMessage keeps the raw body — consumed by ApiErrorMessages.classify/retry logic.
        assertTrue(Strings.CS.contains(failure.getMessage(), body));
    }

    @Test
    void apiExceptionFor_unknownPattern_friendlyMessageIsNull() {
        String body = "{\"error\":{\"message\":\"some new unclassified shape\"}}";
        ApiException failure = AnthropicSdkClient.apiExceptionFor(500, body, Headers.of());
        assertNull(failure.friendlyMessage());
    }

    @Test
    void apiExceptionFor_threadsRetryAfterHeader() {
        Headers headers = Headers.of("Retry-After", "42");
        ApiException failure = AnthropicSdkClient.apiExceptionFor(429, "{}", headers);
        assertEquals(42L, failure.retryAfterSeconds());
    }
}
