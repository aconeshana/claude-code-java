package com.claudecode.core.message;

import org.apache.commons.lang3.Strings;
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
        assertTrue(Strings.CS.contains(result, "temporary capacity issue"));
        assertFalse(Strings.CS.contains(result, body));
    }

    @Test
    void classify_toolUseConcurrencyMismatch() {
        String body = "{\"error\":{\"message\":\"`tool_use` ids were found without `tool_result`"
            + " blocks immediately after\"}}";
        String result = ApiErrorFriendlyText.classify(400, body);
        assertTrue(Strings.CS.contains(result, "tool use concurrency"));
        assertTrue(Strings.CS.contains(result, "/rewind"));
    }

    @Test
    void classify_duplicateToolUseIds() {
        String body = "{\"error\":{\"message\":\"`tool_use` ids must be unique\"}}";
        String result = ApiErrorFriendlyText.classify(400, body);
        assertTrue(Strings.CS.contains(result, "duplicate tool_use ID"));
        assertTrue(Strings.CS.contains(result, "/rewind"));
    }

    @Test
    void classify_creditBalanceTooLow_ignoresStatusCode() {
        String body = "{\"error\":{\"message\":\"Your credit balance is too low\"}}";
        assertEquals("Credit balance is too low", ApiErrorFriendlyText.classify(400, body));
    }

    @Test
    void classify_organizationDisabled_requires400() {
        String body = "{\"error\":{\"message\":\"Your organization has been disabled\"}}";
        assertTrue(Strings.CS.contains(ApiErrorFriendlyText.classify(400, body), "disabled organization"));
        assertNull(ApiErrorFriendlyText.classify(500, body),
            "TS only handles this as a 400 invalid_request_error");
    }

    @Test
    void classify_invalidApiKey_mentionsXApiKeyRegardlessOfStatus() {
        String body = "{\"error\":{\"message\":\"x-api-key header is invalid\"}}";
        assertTrue(Strings.CS.contains(ApiErrorFriendlyText.classify(401, body), "Invalid API key"));
    }

    @Test
    void classify_generic401403_includesExtractedDetailWithoutRawJson() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"bad token\"}}";
        String result = ApiErrorFriendlyText.classify(401, body);
        assertTrue(Strings.CS.contains(result, "Authentication failed"));
        assertTrue(Strings.CS.contains(result, "bad token"));
        assertFalse(Strings.CS.contains(result, "authentication_error"));
    }

    @Test
    void classify_notFound_suggestsModelCommand() {
        String body = "{\"error\":{\"message\":\"model not found\"}}";
        assertTrue(Strings.CS.contains(ApiErrorFriendlyText.classify(404, body), "/model"));
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
