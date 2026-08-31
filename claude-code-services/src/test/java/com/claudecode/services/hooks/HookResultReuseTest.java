package com.claudecode.services.hooks;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class HookResultReuseTest {

    @Test
    void emptyResultsAreCanonical() {
        assertSame(HookResult.allow(), HookResult.allow());
        assertSame(HookResult.skip(), HookResult.skip());
    }
}
