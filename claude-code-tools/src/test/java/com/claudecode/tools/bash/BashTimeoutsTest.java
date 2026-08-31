package com.claudecode.tools.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BashTimeoutsTest {
    @Test
    void usesDefaultsForMissingOrInvalidValues() {
        assertEquals(120_000L, BashTimeouts.defaultTimeoutMs(_ -> null));
        assertEquals(600_000L, BashTimeouts.maxTimeoutMs(_ -> "nope"));
    }

    @Test
    void followsParseIntPrefixAndKeepsMaximumAboveDefault() {
        Map<String, String> env = Map.of(
            "BASH_DEFAULT_TIMEOUT_MS", " 700000ms",
            "BASH_MAX_TIMEOUT_MS", "300000 trailing");
        assertEquals(700_000L, BashTimeouts.defaultTimeoutMs(env::get));
        assertEquals(700_000L, BashTimeouts.maxTimeoutMs(env::get));
    }
}
