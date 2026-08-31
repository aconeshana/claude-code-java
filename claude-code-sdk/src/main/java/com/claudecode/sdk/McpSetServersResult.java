package com.claudecode.sdk;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Result of replacing the dynamically managed MCP server set. */
public record McpSetServersResult(List<String> added, List<String> removed,
                                  Map<String, String> errors) {
    public McpSetServersResult {
        added = List.copyOf(Objects.requireNonNull(added, "added"));
        removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
        errors = Map.copyOf(Objects.requireNonNull(errors, "errors"));
    }
}
