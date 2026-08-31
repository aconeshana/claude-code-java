package com.claudecode.sdk;

import com.claudecode.core.engine.AbortController;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Per-call cancellation and request metadata exposed to an SDK MCP handler.
SDK MCP handler extras.</li></ul>
 */
public record SdkMcpToolContext(AbortController abortController, JsonNode requestMeta) {}
