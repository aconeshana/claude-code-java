package com.claudecode.tools.hints;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClaudeCodeHintStore} — single-slot, once-per-session gating.
 */
class ClaudeCodeHintStoreTest {

    @AfterEach
    void tearDown() {
        ClaudeCodeHintStore.getInstance().resetForTest();
    }

    private static ClaudeCodeHint plugin(String value) {
        return new ClaudeCodeHint(1, "plugin", value, "mycli");
    }

    @Test
    void listenerNotifiedForAcceptedHint() {
        boolean[] notified = {false};
        ClaudeCodeHintStore.getInstance().setListener(_ -> notified[0] = true);
        assertTrue(ClaudeCodeHintStore.getInstance().recordPluginHint(plugin("a@m")));
        assertTrue(notified[0]);
    }

    @Test
    void nonPluginTypeRejected() {
        assertFalse(ClaudeCodeHintStore.getInstance().recordPluginHint(
            new ClaudeCodeHint(1, "other", "x", "c")));
    }

    @Test
    void oncePerSession() {
        ClaudeCodeHintStore store = ClaudeCodeHintStore.getInstance();
        assertTrue(store.recordPluginHint(plugin("a@m")));
        // Second distinct value is rejected because shownThisSession is implied
        // only after markShown — but dedupe by value blocks repeats.
        assertFalse(store.recordPluginHint(plugin("a@m")));
        // A different value before markShown is still accepted (dedupe is per-value).
        assertTrue(store.recordPluginHint(plugin("b@m")));
        store.markShownThisSession();
        assertFalse(store.recordPluginHint(plugin("c@m")));
    }
}
