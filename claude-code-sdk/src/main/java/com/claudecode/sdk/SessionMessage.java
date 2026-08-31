package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/** Historical message returned by {@link ClaudeAgentSdk#getSessionMessages}. */
public record SessionMessage(String type, String uuid, String sessionId, JsonNode message,
                             String parentToolUseId, String timestamp) {}
