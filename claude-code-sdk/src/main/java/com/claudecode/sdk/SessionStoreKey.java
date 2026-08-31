package com.claudecode.sdk;

/**
 * Safe logical key for a main or subagent transcript in a custom store.
session-store key projection.</li></ul>
 */
public record SessionStoreKey(String projectKey, String sessionId, String subpath) {
    public SessionStoreKey(String projectKey, String sessionId) { this(projectKey, sessionId, null); }
}
