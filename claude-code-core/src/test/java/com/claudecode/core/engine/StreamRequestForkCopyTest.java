package com.claudecode.core.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fork copy helpers against the failure mode they exist to prevent:
 * a fork that re-lists {@code StreamRequest}'s components positionally, binds to
 * a shorter convenience constructor as the record grows, and silently drops the
 * new tail components.
 */
class StreamRequestForkCopyTest {

    private static final Runnable ON_STREAMING_FALLBACK = () -> { };
    private static final BiConsumer<Integer, Long> ON_FAST_MODE_FAILURE = (_, _) -> { };

    /** A request with every one of the 22 components set to a distinctive value. */
    private static StreamingClient.StreamRequest fullyPopulated() {
        return new StreamingClient.StreamRequest(
            "claude-sonnet-5",
            32_000,
            "system-prompt",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hello")),
            true,
            List.of(new StreamingClient.StreamRequest.ToolDef(
                "Bash", "Run a command", new ObjectMapper().createObjectNode())),
            new ObjectMapper().createObjectNode().put("kind", "schema"),
            "high",
            "claude-haiku-5",
            8_000,
            "task-budget",
            "auto",
            ON_STREAMING_FALLBACK,
            true,
            "session-1",
            "agent-1",
            true,
            "user",
            new AbortController(),
            4_096,
            true,
            ON_FAST_MODE_FAILURE);
    }

    /**
     * The record carries 22 components, the last two being the Fast Mode pair.
     * If this fails, a component was added and the copy helpers below must be
     * extended to carry it — which is the whole point of having them.
     */
    @Test
    void streamRequestStillHas22Components() {
        assertEquals(22, StreamingClient.StreamRequest.class.getRecordComponents().length,
            "a new component must also be carried by asFork/withModel/withFastMode");
    }

    @Test
    void asForkCarriesEveryComponentExceptTheOnesAForkMustOwn() {
        StreamingClient.StreamRequest parent = fullyPopulated();
        AbortController forkAbort = new AbortController();
        List<StreamingClient.StreamRequest.RequestMessage> forkMessages = List.of(
            new StreamingClient.StreamRequest.RequestMessage("user", "hello"),
            new StreamingClient.StreamRequest.RequestMessage("user", "recap please"));

        StreamingClient.StreamRequest fork =
            parent.asFork(forkMessages, "away_summary", forkAbort);

        // Preserved verbatim — this is the cached prefix and the session's shape.
        assertEquals(parent.model(), fork.model());
        assertEquals(parent.maxTokens(), fork.maxTokens());
        assertEquals(parent.systemPrompt(), fork.systemPrompt());
        assertEquals(parent.tools(), fork.tools());
        assertEquals(parent.effort(), fork.effort());
        assertEquals(parent.fallbackModel(), fork.fallbackModel());
        assertEquals(parent.maxOutputTokensOverride(), fork.maxOutputTokensOverride());
        assertEquals(parent.taskBudget(), fork.taskBudget());
        assertEquals(parent.toolChoice(), fork.toolChoice());
        assertSame(parent.onStreamingFallback(), fork.onStreamingFallback());
        assertEquals(parent.thinkingEnabled(), fork.thinkingEnabled());
        assertEquals(parent.sessionId(), fork.sessionId());
        assertEquals(parent.thinkingBudgetTokens(), fork.thinkingBudgetTokens());

        // The regression this helper exists for.
        assertTrue(fork.fastMode(), "a fork of a Fast Mode turn is itself a fast request");
        assertSame(ON_FAST_MODE_FAILURE, fork.onFastModeFailure(),
            "without the callback a rate-limited fork never enters the cooldown");

        // Owned by the fork.
        assertEquals(forkMessages, fork.messages());
        assertEquals("away_summary", fork.querySource());
        assertSame(forkAbort, fork.abortController());
        assertTrue(fork.skipCacheWrite(),
            "a throwaway fork must not move the main loop's cache breakpoint");
        assertTrue(fork.stream());
        assertNull(fork.jsonSchema(), "a text fork does not inherit a structured-output schema");
        assertNull(fork.agentId(), "the fork is not a sub-agent turn");
    }

    @Test
    void withModelRetargetsAndClearsTheFallbackButKeepsEverythingElse() {
        StreamingClient.StreamRequest parent = fullyPopulated();

        StreamingClient.StreamRequest retried = parent.withModel("claude-haiku-5");

        assertEquals("claude-haiku-5", retried.model());
        assertNull(retried.fallbackModel(),
            "the fallback attempt clears its own fallback so it cannot recurse");

        assertEquals(parent.maxTokens(), retried.maxTokens());
        assertEquals(parent.systemPrompt(), retried.systemPrompt());
        assertEquals(parent.messages(), retried.messages());
        assertEquals(parent.stream(), retried.stream());
        assertEquals(parent.tools(), retried.tools());
        assertEquals(parent.jsonSchema(), retried.jsonSchema());
        assertEquals(parent.effort(), retried.effort());
        assertEquals(parent.maxOutputTokensOverride(), retried.maxOutputTokensOverride());
        assertEquals(parent.taskBudget(), retried.taskBudget());
        assertEquals(parent.toolChoice(), retried.toolChoice());
        assertSame(parent.onStreamingFallback(), retried.onStreamingFallback());
        assertEquals(parent.thinkingEnabled(), retried.thinkingEnabled());
        assertEquals(parent.sessionId(), retried.sessionId());
        assertEquals(parent.agentId(), retried.agentId());
        assertEquals(parent.skipCacheWrite(), retried.skipCacheWrite());
        assertEquals(parent.querySource(), retried.querySource());
        assertSame(parent.abortController(), retried.abortController());
        assertEquals(parent.thinkingBudgetTokens(), retried.thinkingBudgetTokens());
        assertEquals(parent.fastMode(), retried.fastMode());
        assertSame(parent.onFastModeFailure(), retried.onFastModeFailure());
    }

    @Test
    void withFastModeSetsOnlyTheFastModePair() {
        StreamingClient.StreamRequest parent = fullyPopulated().withFastMode(false, null);
        assertFalse(parent.fastMode());
        assertNull(parent.onFastModeFailure());

        AtomicReference<String> cooled = new AtomicReference<>();
        StreamingClient.StreamRequest fast = parent.withFastMode(
            true, (status, retryAfter) -> cooled.set(status + ":" + retryAfter));

        assertTrue(fast.fastMode());
        assertNotNull(fast.onFastModeFailure());
        fast.onFastModeFailure().accept(529, 600L);
        assertEquals("529:600", cooled.get());

        assertEquals(parent.model(), fast.model());
        assertEquals(parent.messages(), fast.messages());
        assertEquals(parent.querySource(), fast.querySource());
        assertEquals(parent.fallbackModel(), fast.fallbackModel());
        assertSame(parent.abortController(), fast.abortController());
        assertEquals(parent.skipCacheWrite(), fast.skipCacheWrite());
        assertEquals(parent.agentId(), fast.agentId());
    }

    /**
     * The trap this whole change removes: a positional call one component short
     * of the canonical shape still compiles, binding to a lossy overload.
     */
    @Test
    @SuppressWarnings("deprecation") // deliberately exercising the lossy overload
    void theLegacy20ArgConstructorStillDropsTheFastModePair() {
        StreamingClient.StreamRequest legacy = new StreamingClient.StreamRequest(
            "claude-sonnet-5", 32_000, "system-prompt",
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", "hello")),
            true, List.of(), null, "high", "claude-haiku-5", 8_000, "task-budget",
            "auto", ON_STREAMING_FALLBACK, true, "session-1", "agent-1", true,
            "user", new AbortController(), 4_096);

        assertFalse(legacy.fastMode(), "documents why forks must not use this shape");
        assertNull(legacy.onFastModeFailure());
    }
}
