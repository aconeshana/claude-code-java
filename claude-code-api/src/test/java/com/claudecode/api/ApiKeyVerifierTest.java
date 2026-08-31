package com.claudecode.api;

import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ApiKeyVerifierTest {

    /** A key that authenticates: createMessage returns normally. */
    static final LlmClient OK_CLIENT = new StubClient(null);

    /** Verifier must never touch the client when non-interactive. */
    @Test
    void nonInteractiveSkipsVerification() {
        boolean[] called = {false};
        LlmClient spy = new StubClient(null) {
            @Override
            public ApiMessage createMessage(CreateMessageRequest request) {
                called[0] = true;
                return null;
            }
        };
        assertTrue(ApiKeyVerifier.verify("any-key", true, spy));
        assertFalse(called[0], "non-interactive must skip the probe entirely");
    }

    @Test
    void blankKeySkipsVerification() {
        assertTrue(ApiKeyVerifier.verify(null, false, OK_CLIENT));
        assertTrue(ApiKeyVerifier.verify("   ", false, OK_CLIENT));
    }

    @Test
    void validKeyReturnsTrue() {
        assertTrue(ApiKeyVerifier.verify("sk-ant-valid", false, OK_CLIENT));
    }

    @Test
    void invalidXApiKeyReturnsFalse() {
        LlmClient bad = new StubClient(
            new ApiException("API request failed: {\"type\":\"error\","
                + "\"error\":{\"type\":\"authentication_error\","
                + "\"message\":\"invalid x-api-key\"}}", 401));
        assertFalse(ApiKeyVerifier.verify("sk-ant-bad", false, bad),
            "explicit invalid x-api-key must be reported as false");
    }

    @Test
    void otherErrorsAreRethrownNotSwallowed() {
        LlmClient serverError = new StubClient(
            new ApiException("API request failed: {\"type\":\"error\","
                + "\"error\":{\"type\":\"api_error\",\"message\":\"overloaded\"}}", 500));
        ApiException thrown = assertThrows(ApiException.class,
            () -> ApiKeyVerifier.verify("sk-ant-maybe", false, serverError));
        assertEquals(500, thrown.statusCode());
    }

    /** Minimal LlmClient stub: returns a canned message or throws. */
    static class StubClient implements LlmClient {
        private final ApiException toThrow;
        StubClient(ApiException toThrow) { this.toThrow = toThrow; }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            if (toThrow != null) throw toThrow;
            return ApiMessage.builder().id("msg_test").model("haiku").build();
        }

        @Override
        public String getModel() {
            return "haiku";
        }
    }
}
