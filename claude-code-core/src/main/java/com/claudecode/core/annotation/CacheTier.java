package com.claudecode.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents which prompt-caching tier a model request belongs to, so the
 * billing behavior of every non-main-loop LLM call site is readable at a
 * glance.
 *
 * <p>A tier answers one question: <b>will this request's prefix ever be
 * replayed verbatim by a later request?</b> Anthropic prompt caching bills
 * cache writes at 1.25x and cache reads at 0.1x, so a cache marker on a
 * never-replayed prefix is a pure 25% premium with zero hits.
 *
 * <ul>
 *   <li>{@link Tier#MAIN_LOOP} — the conversation itself; every turn replays
 *       the whole prefix, so the marker always pays for itself. This is the
 *       {@code CreateMessageRequest} default ({@code promptCachingEnabled}
 *       = true), and also fits callers that issue multiple requests sharing
 *       one prefix (e.g. the two-stage auto-mode classifier: stage 2 replays
 *       stage 1's transcript prefix and hits its cache).</li>
 *   <li>{@link Tier#FORKED_PREFIX} — the request forks the main conversation
 *       (compact, agent summary, away summary) and appends a one-shot prompt.
 *       The prefix keeps its markers at the main loop's breakpoint positions
 *       (reads hit the main conversation's cache), while the appended tail is
 *       left uncached ({@code skipCacheWrite} = true, 236
 *       {@code cacheSafeParams + skipCacheWrite:!0}). Never disable caching
 *       entirely here: the prefix read discount usually outweighs the write
 *       premium on long conversations.</li>
 *   <li>{@link Tier#ONE_SHOT} — a standalone prompt with no shared prefix and
 *       no internal replay (permission explainer, hook evaluators, insights
 *       facets, session search, title generation). No marker at all
 *       ({@code promptCachingEnabled} = false, 236 {@code $ae}'s
 *       {@code enablePromptCaching ?? false}); plain 1x billing is the
 *       optimum.</li>
 * </ul>
 *
 * <p>Apply to the method or class that <b>builds</b> the request so the tier
 * sits next to the caching knobs it documents.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CacheTier {

    /** The prompt-caching billing tier of the annotated request builder. */
    Tier value();

    /** The observable billing tiers for a model request. */
    enum Tier {
        /** Full caching; prefix is replayed by later requests (main loop, retry chains). */
        MAIN_LOOP,
        /** Forked main-conversation prefix with an uncached one-shot tail (skipCacheWrite). */
        FORKED_PREFIX,
        /** Standalone never-replayed prompt; no cache markers at all. */
        ONE_SHOT
    }
}