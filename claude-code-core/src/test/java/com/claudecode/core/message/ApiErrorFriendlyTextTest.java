package com.claudecode.core.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorFriendlyTextTest {

    @Test
    void classify_rateLimit_extractsNestedErrorMessage() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}";
        assertEquals("API Error: Request rejected (429) · slow down",
            ApiErrorFriendlyText.classify(429, body));
    }

    @Test
    void classify_rateLimit_extraUsageForLongContext_getsItsOwnMessage() {
        String body = "{\"error\":{\"message\":\"Extra usage is required for long context\"}}";
        assertEquals("API Error: Extra usage is required for 1M context"
            + " · use /model to switch to standard context",
            ApiErrorFriendlyText.classify(429, body));
    }

    @Test
    void classify_rateLimit_unparsableBodyFallsBackToGenericTextWithoutLeakingRawBody() {
        String body = "not json at all";
        String result = ApiErrorFriendlyText.classify(429, body);
        assertTrue(result.contains("temporary capacity issue"));
        assertFalse(result.contains(body));
    }

    @Test
    void classify_toolUseConcurrencyMismatch() {
        String body = "{\"error\":{\"message\":\"`tool_use` ids were found without `tool_result`"
            + " blocks immediately after\"}}";
        String result = ApiErrorFriendlyText.classify(400, body);
        assertTrue(result.contains("tool use concurrency"));
        assertTrue(result.contains("/rewind"));
    }

    @Test
    void classify_duplicateToolUseIds() {
        String body = "{\"error\":{\"message\":\"`tool_use` ids must be unique\"}}";
        String result = ApiErrorFriendlyText.classify(400, body);
        assertTrue(result.contains("duplicate tool_use ID"));
        assertTrue(result.contains("/rewind"));
    }

    @Test
    void classify_creditBalanceTooLow_ignoresStatusCode() {
        String body = "{\"error\":{\"message\":\"Your credit balance is too low\"}}";
        assertEquals("Credit balance is too low", ApiErrorFriendlyText.classify(400, body));
    }

    @Test
    void classify_organizationDisabled_requires400() {
        String body = "{\"error\":{\"message\":\"Your organization has been disabled\"}}";
        assertTrue(ApiErrorFriendlyText.classify(400, body).contains("disabled organization"));
        assertNull(ApiErrorFriendlyText.classify(500, body),
            "TS only handles this as a 400 invalid_request_error");
    }

    @Test
    void classify_invalidApiKey_mentionsXApiKeyRegardlessOfStatus() {
        String body = "{\"error\":{\"message\":\"x-api-key header is invalid\"}}";
        assertTrue(ApiErrorFriendlyText.classify(401, body).contains("Invalid API key"));
    }

    @Test
    void classify_generic401403_includesExtractedDetailWithoutRawJson() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad token\"}}";
        String result = ApiErrorFriendlyText.classify(401, body);
        assertTrue(result.contains("Authentication failed"));
        assertTrue(result.contains("bad token"));
        assertFalse(result.contains("authentication_error"));
    }

    @Test
    void classify_notFound_suggestsModelCommand() {
        String body = "{\"error\":{\"message\":\"model not found\"}}";
        assertTrue(ApiErrorFriendlyText.classify(404, body).contains("/model"));
    }

    @Test
    void classify_unknownPattern_returnsNull() {
        assertNull(ApiErrorFriendlyText.classify(400, "{\"error\":{\"message\":\"some new shape\"}}"));
        assertNull(ApiErrorFriendlyText.classify(500, "{}"));
    }

    @Test
    void connectionFriendlyMessage_timeoutIsRecognized() {
        assertEquals("Request timed out",
            ApiErrorFriendlyText.connectionFriendlyMessage("Read timeout after 30000ms"));
    }

    @Test
    void connectionFriendlyMessage_otherFailuresReturnNull() {
        assertNull(ApiErrorFriendlyText.connectionFriendlyMessage("Connection refused"));
        assertNull(ApiErrorFriendlyText.connectionFriendlyMessage(null));
    }
}
