package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP annotations plus Anthropic tool-search metadata.
optional {@code tool} extras.</li></ul>
 */
public record SdkMcpToolExtras(JsonNode annotations, String searchHint, boolean alwaysLoad) {}
