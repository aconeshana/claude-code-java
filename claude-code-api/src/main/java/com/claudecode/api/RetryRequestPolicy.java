package com.claudecode.api;

import java.util.Set;

/**
 * Per-request retry context carried as an OkHttp request tag.
 *
 * <ul>
 *   <li>retries 529 only
 *       for query sources whose result blocks the user.</li>
 * </ul>
 */
public record RetryRequestPolicy(boolean retryOverload) {

    private static final Set<String> FOREGROUND_529_SOURCES = Set.of(
        "repl_main_thread",
        "repl_main_thread:outputStyle:custom",
        "repl_main_thread:outputStyle:Explanatory",
        "repl_main_thread:outputStyle:Learning",
        "sdk",
        "agent:custom",
        "agent:default",
        "agent:builtin",
        "compact",
        "hook_agent",
        "hook_prompt",
        "verification_agent",
        "side_question",
        "auto_mode",
        "bash_classifier",
        "user");

    public static RetryRequestPolicy forQuerySource(String querySource) {
        return new RetryRequestPolicy(
            querySource == null || FOREGROUND_529_SOURCES.contains(querySource));
    }
}
