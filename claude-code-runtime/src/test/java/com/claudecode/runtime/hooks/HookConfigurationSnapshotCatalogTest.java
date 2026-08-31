package com.claudecode.runtime.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookConfigurationSnapshotCatalogTest {

    @Test
    void runtimeCatalogMapsAll197EventNames() {
        assertEquals("PostToolBatch",
            HookConfigurationSnapshot.HookEvent.valueOf("POST_TOOL_BATCH").displayName());
        assertEquals("UserPromptExpansion",
            HookConfigurationSnapshot.HookEvent.valueOf("USER_PROMPT_EXPANSION").displayName());
        assertEquals("MessageDisplay",
            HookConfigurationSnapshot.HookEvent.valueOf("MESSAGE_DISPLAY").displayName());
    }
}
