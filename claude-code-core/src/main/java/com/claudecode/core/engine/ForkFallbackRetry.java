package com.claudecode.core.engine;

import org.apache.commons.lang3.StringUtils;

import java.util.function.Function;

/**
 * Runs a forked request, retrying once on the request's configured fallback
 * model when the primary reports itself overloaded.
 *
 * <p>The API adapter signals this by throwing {@link FallbackTriggeredError},
 * which it raises only when the primary is overloaded <em>and</em> the request
 * actually carries a fallback model (from {@code --fallback-model}). The signal
 * is a {@link RuntimeException}, so any fork path with a broad
 * {@code catch (Exception)} swallows it and degrades a retryable condition into
 * a hard failure — which is exactly what {@code /recap} did while the side
 * question, handling it, transparently recovered under the same conditions.
 *
 * <p>Shared rather than copied: "retry this fork on its fallback model" existed
 * once in the side-question path and was needed a second time by the recap
 * fork. Two copies would drift. It lives in core because both callers'
 * modules ({@code claude-code-cli} and {@code claude-code-services}) depend on
 * core, while services must not depend on cli.
 */
public final class ForkFallbackRetry {

    private ForkFallbackRetry() {}

    /**
     * Applies {@code attempt} to {@code request}; if that raises
     * {@link FallbackTriggeredError} and the request names a fallback model,
     * applies it again to the same request aimed at that model.
     *
     * <p>The retry clears the fallback (via
     * {@link StreamingClient.StreamRequest#withModel}) so an overloaded fallback
     * cannot trigger a second, unbounded hop.
     *
     * @param request the fork to run
     * @param attempt consumes the fork and produces its result; invoked at most twice
     * @param <T>     the caller's own result type
     * @return {@code attempt}'s result, from the primary or the fallback model
     * @throws FallbackTriggeredError when no fallback model is configured, so a
     *                                caller without one sees the original signal
     */
    public static <T> T call(
            StreamingClient.StreamRequest request,
            Function<StreamingClient.StreamRequest, T> attempt) {
        try {
            return attempt.apply(request);
        } catch (FallbackTriggeredError fallback) {
            if (StringUtils.isBlank(request.fallbackModel())) throw fallback;
            return attempt.apply(request.withModel(request.fallbackModel()));
        }
    }
}
