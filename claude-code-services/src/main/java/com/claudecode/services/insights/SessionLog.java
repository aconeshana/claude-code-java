package com.claudecode.services.insights;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;


public record SessionLog(
    String sessionId,
    List<JsonNode> messages,
    String leafUuid,
    String firstPrompt,
    String summary,
    String projectPath
) {
    public SessionLog {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** Compatibility constructor for existing tests and injected callers. */
    public SessionLog(String sessionId, List<JsonNode> messages) {
        this(sessionId, messages, null, null, null, null);
    }
}
