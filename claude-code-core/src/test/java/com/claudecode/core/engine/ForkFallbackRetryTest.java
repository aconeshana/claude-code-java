package com.claudecode.core.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The fork-level "retry on the configured fallback model" contract. */
class ForkFallbackRetryTest {

    private static final BiConsumer<Integer, Long> ON_FAST_MODE_FAILURE = (_, _) -> { };

    private static StreamingClient.StreamRequest request(String model, String fallbackModel) {
        return new StreamingClient.StreamRequest(
            model,                  // model
            32_000,                 // maxTokens
            "system-prompt",        // systemPrompt
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hi")),
            true,                   // stream
            List.of(),              // tools
            null,                   // jsonSchema
            null,                   // effort
            fallbackModel,          // fallbackModel
            null,                   // maxOutputTokensOverride
            null,                   // taskBudget
            null,                   // toolChoice
            null,                   // onStreamingFallback
            true,                   // thinkingEnabled
            "session-1",            // sessionId
            null,                   // agentId
            true,                   // skipCacheWrite
            "away_summary",         // querySource
            null,                   // abortController
            null,                   // thinkingBudgetTokens
            true,                   // fastMode
            ON_FAST_MODE_FAILURE);  // onFastModeFailure
    }

    @Test
    void returnsTheFirstAttemptWhenNothingIsOverloaded() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", "claude-haiku-5");
        List<String> attempts = new ArrayList<>();

        String result = ForkFallbackRetry.call(fork, attempt -> {
            attempts.add(attempt.model());
            return "primary";
        });

        assertEquals("primary", result);
        assertEquals(List.of("claude-sonnet-5"), attempts, "no retry when the primary answers");
    }

    @Test
    void retriesOnTheFallbackModelWhenThePrimaryIsOverloaded() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", "claude-haiku-5");
        List<StreamingClient.StreamRequest> attempts = new ArrayList<>();

        String result = ForkFallbackRetry.call(fork, attempt -> {
            attempts.add(attempt);
            if (attempts.size() == 1) {
                throw new FallbackTriggeredError(attempt.model(), attempt.fallbackModel());
            }
            return "fallback";
        });

        assertEquals("fallback", result);
        assertEquals(2, attempts.size());
        assertEquals("claude-haiku-5", attempts.get(1).model());
        assertNull(attempts.get(1).fallbackModel(),
            "clearing the fallback bounds the retry to a single hop");
        assertEquals(fork.querySource(), attempts.get(1).querySource(),
            "the retry is the same fork, only retargeted");
        assertEquals(fork.fastMode(), attempts.get(1).fastMode());
        assertSame(fork.onFastModeFailure(), attempts.get(1).onFastModeFailure());
    }

    @Test
    void rethrowsWhenNoFallbackModelIsConfigured() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", null);
        List<String> attempts = new ArrayList<>();

        assertThrows(FallbackTriggeredError.class, () ->
            ForkFallbackRetry.call(fork, attempt -> {
                attempts.add(attempt.model());
                throw new FallbackTriggeredError(attempt.model(), "unused");
            }));

        assertEquals(1, attempts.size(), "nothing to retry on");
    }

    @Test
    void rethrowsWhenTheFallbackModelIsBlank() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", "   ");

        assertThrows(FallbackTriggeredError.class, () ->
            ForkFallbackRetry.call(fork, attempt -> {
                throw new FallbackTriggeredError(attempt.model(), "unused");
            }));
    }

    @Test
    void anOverloadedFallbackIsNotRetriedAgain() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", "claude-haiku-5");
        List<String> attempts = new ArrayList<>();

        assertThrows(FallbackTriggeredError.class, () ->
            ForkFallbackRetry.call(fork, attempt -> {
                attempts.add(attempt.model());
                throw new FallbackTriggeredError(attempt.model(), "claude-haiku-5");
            }));

        assertEquals(List.of("claude-sonnet-5", "claude-haiku-5"), attempts,
            "the second failure escapes rather than looping");
    }

    @Test
    void otherFailuresArePropagatedUnchanged() {
        StreamingClient.StreamRequest fork = request("claude-sonnet-5", "claude-haiku-5");
        IllegalStateException boom = new IllegalStateException("boom");

        assertSame(boom, assertThrows(IllegalStateException.class, () ->
            ForkFallbackRetry.call(fork, _ -> { throw boom; })));
    }
}
