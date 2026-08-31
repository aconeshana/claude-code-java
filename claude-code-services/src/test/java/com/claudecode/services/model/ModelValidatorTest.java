package com.claudecode.services.model;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.ApiException;
import com.claudecode.api.ApiMessage;
import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.api.StreamEvent;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class ModelValidatorTest {

    /** Minimal fake — throws {@code toThrow} on createMessage, else returns null. */
    private static final class FakeLlmClient implements LlmClient {
        private final RuntimeException toThrow;
        private final ApiMessage response;
        private final long delayMs;
        private boolean called;
        private CreateMessageRequest request;
        FakeLlmClient(RuntimeException toThrow) { this(toThrow, null, 0); }
        FakeLlmClient(RuntimeException toThrow, ApiMessage response, long delayMs) {
            this.toThrow = toThrow;
            this.response = response;
            this.delayMs = delayMs;
        }
        @Override public Iterator<StreamEvent> createMessageStream(CreateMessageRequest r) {
            throw new UnsupportedOperationException();
        }
        @Override public ApiMessage createMessage(CreateMessageRequest r) {
            called = true;
            request = r;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (toThrow != null) throw toThrow;
            return response;
        }
        @Override public String getModel() { return "fake"; }
    }

    @BeforeEach
    void resetCostState() {
        SessionCostState.get().reset();
    }

    @Test
    void emptyIsInvalid() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(null));
        ModelValidator.Result r = v.validate("   ");
        assertFalse(r.valid());
        assertEquals("Model name cannot be empty", r.error());
    }

    @Test
    void aliasShortCircuitsWithoutApiCall() {
        FakeLlmClient client = new FakeLlmClient(new RuntimeException("must not be called"));
        ModelValidator v = new ModelValidator(client);
        assertTrue(v.validate("opus").valid());
        assertTrue(v.validate("sol").valid());
        assertTrue(v.validate("luna").valid());
        assertFalse(client.called, "alias must not trigger an API probe");
    }

    @Test
    void nullClientAcceptsWithoutProbe() {
        ModelValidator v = new ModelValidator(null);
        assertTrue(v.validate("claude-custom-xyz-nullclient").valid());
    }

    @Test
    void validCustomModelProbesAndCaches() {
        FakeLlmClient client = new FakeLlmClient(null);
        ModelValidator v = new ModelValidator(client);
        assertTrue(v.validate("claude-valid-probe-unique").valid());
        assertTrue(client.called);
        assertEquals("model_validation", client.request.querySource());
    }

    @Test
    void successfulValidationContributesToReleasedCumulativeApiMetrics() {
        Usage usage = new Usage(7, 3, 0, 0);
        ApiMessage response = ApiMessage.builder()
            .model("claude-served-validation")
            .usage(usage)
            .build();
        ModelValidator validator = new ModelValidator(
            new FakeLlmClient(null, response, 5));

        assertTrue(validator.validate("claude-valid-metrics-unique").valid());

        assertEquals(usage,
            SessionCostState.get().usageByModel().get("claude-served-validation"));
        assertTrue(SessionCostState.get().apiDurationMs() >= 1L);
    }

    @Test
    void notFound404() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(new ApiException("nope", 404)));
        ModelValidator.Result r = v.validate("claude-missing-404-unique");
        assertFalse(r.valid());
        assertEquals("Model 'claude-missing-404-unique' not found", r.error());
    }

    @Test
    void notFoundViaErrorType() {
        ModelValidator v = new ModelValidator(
            new FakeLlmClient(new ApiException("bad", 400, "not_found_error")));
        assertEquals("Model 'claude-missing-type-unique' not found",
            v.validate("claude-missing-type-unique").error());
    }

    @Test
    void authFailure() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(new ApiException("x", 401)));
        assertEquals("Authentication failed. Please check your API credentials.",
            v.validate("claude-auth-401-unique").error());
    }

    @Test
    void networkError() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(new ApiException("io", 0)));
        assertEquals("Network error. Please check your internet connection.",
            v.validate("claude-net-0-unique").error());
    }

    @Test
    void genericApiError() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(new ApiException("boom", 500)));
        ModelValidator.Result r = v.validate("claude-500-unique");
        assertFalse(r.valid());
        assertTrue(Strings.CS.startsWith(r.error(), "API error:"));
    }

    @Test
    void unknownExceptionIsWrapped() {
        ModelValidator v = new ModelValidator(new FakeLlmClient(new IllegalStateException("weird")));
        ModelValidator.Result r = v.validate("claude-weird-unique");
        assertFalse(r.valid());
        assertTrue(Strings.CS.startsWith(r.error(), "Unable to validate model:"));
    }
}
