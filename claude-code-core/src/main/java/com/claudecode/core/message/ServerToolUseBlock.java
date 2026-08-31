package com.claudecode.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A server-side tool use content block — the model invoking a tool that
 * Anthropic executes on its own infrastructure (e.g. {@code web_search}),
 * as opposed to {@link ToolUseBlock} which the client must execute and
 * answer with a {@code tool_result}. The matching result arrives inline in
 * the same turn as a {@link WebSearchToolResultBlock}, not a client
 * tool_result round-trip.
 * <p>
 * {@code input} is an empty shell at {@code content_block_start} and fills
 * in via {@code input_json_delta} events, same as {@link ToolUseBlock}.
 */
public record ServerToolUseBlock(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("input") JsonNode input
) implements ContentBlock {

    @JsonCreator
    public ServerToolUseBlock {
    }
}
