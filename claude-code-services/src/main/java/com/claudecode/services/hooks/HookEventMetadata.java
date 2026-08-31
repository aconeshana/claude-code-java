package com.claudecode.services.hooks;

/**
 * Display metadata for a hook event — summary, description, and optional matcher hints.
 */
public record HookEventMetadata(
    /** Short one-line summary shown in the event list (e.g., "Before tool calls"). */
    String summary,
    /** Longer description shown in the detail view. */
    String description,
    /** Matcher hints, or null when the event doesn't support pattern matching. */
    MatcherMetadata matcherMetadata
) {
    /**
     * Describes how the matcher field is interpreted for a given event.
     */
    public record MatcherMetadata(
        /** Example placeholder text for the matcher field (e.g., "bash|read|write"). */
        String matcherPlaceholder,
        /**
         * Semantic type of the matcher — {@code "tool_name"} for tool-scoped events,
         * {@code "session_id"} for session-scoped events, etc.
         */
        String matcherType
    ) {}
}
