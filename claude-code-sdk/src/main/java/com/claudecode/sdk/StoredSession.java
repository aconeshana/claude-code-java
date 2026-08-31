package com.claudecode.sdk;

/**
 * Minimal session enumeration result for custom-store continue semantics.
stored session listing metadata.</li></ul>
 */
public record StoredSession(String sessionId, long mtime) {}
