package com.claudecode.runtime.query;

/** Server condition that temporarily suspended Fast Mode. */
public enum FastModeCooldownReason {
    RATE_LIMIT,
    OVERLOADED
}
