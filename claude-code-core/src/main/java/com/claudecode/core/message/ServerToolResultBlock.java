package com.claudecode.core.message;

import com.claudecode.core.annotation.Explanation;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Provider-executed tool result that is not limited to Anthropic web search.
 * Covers OpenAI Responses hosted-tool items and Anthropic server-tool result
 * block types.
 */
@Explanation("Canonical provider-executed tool result for non-Anthropic protocols")
public record ServerToolResultBlock(
    @JsonProperty("tool_use_id") String toolUseId,
    @JsonProperty("name") String name,
    @JsonProperty("content") JsonNode content,
    @JsonProperty("is_error") boolean isError,
    @JsonProperty("provider_type") @JsonInclude(JsonInclude.Include.NON_NULL) String providerType
) implements ContentBlock {

    @JsonCreator
    public ServerToolResultBlock {
    }
}
