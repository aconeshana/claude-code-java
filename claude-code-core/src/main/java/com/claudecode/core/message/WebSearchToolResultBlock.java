package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A server-side web search result content block — Anthropic runs the search
 * on its own infrastructure and inlines the results (or an error) in the
 * same turn; there is no client {@code tool_result} round-trip for this
 * block. Arrives complete at {@code content_block_start} (no deltas follow).
 * <p>
 * {@code content} carries the hit list on success and is {@code null} when
 * {@code errorCode} is set — matches the Anthropic API's
 * {@code WebSearchToolResultError} union, where {@code content} is either an
 * array of hits or a {@code {error_code}} object.
 */
public record WebSearchToolResultBlock(
    @JsonProperty("tool_use_id") String toolUseId,
    @JsonProperty("content") List<Hit> content,
    @JsonProperty("error_code") String errorCode
) implements ContentBlock {

    @JsonCreator
    public WebSearchToolResultBlock {
    }

    /** One search hit, including the opaque fields required for lossless history replay. */
    public record Hit(
        @JsonProperty("title") String title,
        @JsonProperty("url") String url,
        @JsonProperty("encrypted_content") String encryptedContent,
        @JsonProperty("page_age") String pageAge
    ) {
        public Hit(String title, String url) {
            this(title, url, null, null);
        }
    }

    /**
     * Parses the Anthropic wire shape, where {@code content} itself is
     * polymorphic (an array of hits on success, or a {@code {error_code}}
     * object on failure) rather than a sibling field. Called directly by
     * {@code AnthropicSdkClient.parseContentBlock} — not a Jackson creator;
     * the compact constructor above handles this record's own flat
     * serialized shape (session round-trips, property tests).
     */
    public static WebSearchToolResultBlock fromJson(String toolUseId, JsonNode contentNode) {
        if (contentNode != null && contentNode.isArray()) {
            List<Hit> hits = new ArrayList<>(contentNode.size());
            for (JsonNode item : contentNode) {
                hits.add(new Hit(
                    item.has("title") ? item.get("title").asText() : "",
                    item.has("url") ? item.get("url").asText() : "",
                    item.hasNonNull("encrypted_content")
                        ? item.get("encrypted_content").asText() : null,
                    item.hasNonNull("page_age") ? item.get("page_age").asText() : null));
            }
            return new WebSearchToolResultBlock(toolUseId, List.copyOf(hits), null);
        }
        String errorCode = contentNode != null && contentNode.has("error_code")
            ? contentNode.get("error_code").asText()
            : "unknown_error";
        return new WebSearchToolResultBlock(toolUseId, null, errorCode);
    }
}
