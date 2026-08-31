package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Token usage statistics from an API response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Usage(
    @JsonProperty("input_tokens") long inputTokens,
    @JsonProperty("output_tokens") long outputTokens,
    @JsonProperty("cache_creation_input_tokens") long cacheCreationInputTokens,
    @JsonProperty("cache_read_input_tokens") long cacheReadInputTokens,
    @JsonProperty("server_tool_use") ServerToolUse serverToolUse,
    @JsonProperty("service_tier") String serviceTier,
    @JsonProperty("cache_creation") CacheCreation cacheCreation,
    @JsonProperty("inference_geo") String inferenceGeo,
    @JsonProperty("iterations") List<JsonNode> iterations,
    @JsonProperty("speed") String speed,
    @Explanation("Retains OpenAI total_tokens for Codex-compatible context accounting")
    @JsonProperty("total_tokens") @JsonInclude(JsonInclude.Include.NON_NULL) Long reportedTotalTokens
) {

    /**
     * Nested {@code server_tool_use} counts — matches the API's
     * {@code { web_search_requests, web_fetch_requests }}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerToolUse(
        @JsonProperty("web_search_requests") long webSearchRequests,
        @JsonProperty("web_fetch_requests") long webFetchRequests
    ) {
        public static final ServerToolUse ZERO = new ServerToolUse(0, 0);

        public ServerToolUse add(ServerToolUse other) {
            if (other == null) return this;
            return new ServerToolUse(
                this.webSearchRequests + other.webSearchRequests,
                this.webFetchRequests + other.webFetchRequests);
        }
    }

    /** Cache writes split by the API's one-hour and five-minute TTL classes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheCreation(
        @JsonProperty("ephemeral_1h_input_tokens") long ephemeral1hInputTokens,
        @JsonProperty("ephemeral_5m_input_tokens") long ephemeral5mInputTokens
    ) {
        public static final CacheCreation ZERO = new CacheCreation(0, 0);

        CacheCreation add(CacheCreation other) {
            if (other == null) return this;
            return new CacheCreation(
                ephemeral1hInputTokens + other.ephemeral1hInputTokens,
                ephemeral5mInputTokens + other.ephemeral5mInputTokens);
        }
    }

    /** Empty usage constant — zero tokens across all fields. */
    public static final Usage EMPTY = new Usage(
        0, 0, 0, 0, ServerToolUse.ZERO, "standard", CacheCreation.ZERO,
        "", List.of(), "standard", null);

    @JsonCreator
    public Usage {
        if (serverToolUse == null) serverToolUse = ServerToolUse.ZERO;
    }

/**
     * Backward-compatible constructor with server-tool counts and.
     */
    public Usage(long inputTokens, long outputTokens,
                 long cacheCreationInputTokens, long cacheReadInputTokens,
                 ServerToolUse serverToolUse) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
            serverToolUse, "standard", CacheCreation.ZERO, "", List.of(), "standard", null);
    }

    /** Backward-compatible full Anthropic constructor predating OpenAI total_tokens. */
    public Usage(long inputTokens, long outputTokens,
                 long cacheCreationInputTokens, long cacheReadInputTokens,
                 ServerToolUse serverToolUse, String serviceTier,
                 CacheCreation cacheCreation, String inferenceGeo,
                 List<JsonNode> iterations, String speed) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
            serverToolUse, serviceTier, cacheCreation, inferenceGeo, iterations, speed, null);
    }

    /**
     * Backward-compatible 4-arg constructor (no server-tool-use) — keeps the
     * many existing {@code new Usage(i, o, cc, cr)} call sites working.
     */
    public Usage(long inputTokens, long outputTokens,
                 long cacheCreationInputTokens, long cacheReadInputTokens) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
            ServerToolUse.ZERO, "standard", CacheCreation.ZERO, "", List.of(), "standard", null);
    }

    /** OpenAI response usage with an optional provider-reported total snapshot. */
    public Usage(long inputTokens, long outputTokens,
                 long cacheCreationInputTokens, long cacheReadInputTokens,
                 Long reportedTotalTokens) {
        this(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
            ServerToolUse.ZERO, "standard", CacheCreation.ZERO, "", List.of(), "standard",
            reportedTotalTokens);
    }

    /**
     * Adds another Usage to this one, returning a new Usage with accumulated values.
     */
    public Usage add(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(
            this.inputTokens + other.inputTokens,
            this.outputTokens + other.outputTokens,
            this.cacheCreationInputTokens + other.cacheCreationInputTokens,
            this.cacheReadInputTokens + other.cacheReadInputTokens,
            this.serverToolUse.add(other.serverToolUse),
            other.serviceTier != null ? other.serviceTier : this.serviceTier,
            (this.cacheCreation != null ? this.cacheCreation : CacheCreation.ZERO)
                .add(other.cacheCreation),
            other.inferenceGeo != null ? other.inferenceGeo : this.inferenceGeo,
            other.iterations != null ? other.iterations : this.iterations,
            other.speed != null ? other.speed : this.speed,
            null
        );
    }

    /**
     * Applies a newer cumulative streaming usage snapshot.
     */
    public Usage updateCumulative(Usage newer) {
        if (newer == null) return this;
        ServerToolUse currentTools = serverToolUse != null ? serverToolUse : ServerToolUse.ZERO;
        ServerToolUse newerTools = newer.serverToolUse != null
            ? newer.serverToolUse : ServerToolUse.ZERO;
        CacheCreation currentCache = cacheCreation != null ? cacheCreation : CacheCreation.ZERO;
        CacheCreation newerCache = newer.cacheCreation != null
            ? newer.cacheCreation : CacheCreation.ZERO;
        long detailedCacheCreation = newerCache.ephemeral1hInputTokens
            + newerCache.ephemeral5mInputTokens;
        CacheCreation mergedCache = detailedCacheCreation > 0 ? new CacheCreation(
            newerCache.ephemeral1hInputTokens > 0
                ? newerCache.ephemeral1hInputTokens : currentCache.ephemeral1hInputTokens,
            newerCache.ephemeral5mInputTokens > 0
                ? newerCache.ephemeral5mInputTokens : currentCache.ephemeral5mInputTokens)
            : currentCache;
        return new Usage(
            newer.inputTokens > 0 ? newer.inputTokens : inputTokens,
            newer.outputTokens,
            newer.cacheCreationInputTokens > 0
                ? newer.cacheCreationInputTokens
                : detailedCacheCreation > 0 ? detailedCacheCreation : cacheCreationInputTokens,
            newer.cacheReadInputTokens > 0
                ? newer.cacheReadInputTokens : cacheReadInputTokens,
            new ServerToolUse(
                newerTools.webSearchRequests > 0
                    ? newerTools.webSearchRequests : currentTools.webSearchRequests,
                newerTools.webFetchRequests > 0
                    ? newerTools.webFetchRequests : currentTools.webFetchRequests),
            StringUtils.isNotBlank(newer.serviceTier) ? newer.serviceTier : serviceTier,
            mergedCache,
            StringUtils.isNotBlank(newer.inferenceGeo) ? newer.inferenceGeo : inferenceGeo,
            newer.iterations != null && !newer.iterations.isEmpty()
                ? newer.iterations : iterations,
            StringUtils.isNotBlank(newer.speed) ? newer.speed : speed,
            newer.reportedTotalTokens != null ? newer.reportedTotalTokens : reportedTotalTokens
        );
    }

    /** Web-search request count from {@link #serverToolUse} (0 when absent). */
    public long webSearchRequests() {
        return serverToolUse == null ? 0 : serverToolUse.webSearchRequests();
    }

    /**
     * Returns the total number of tokens (input + output).
     */
    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
