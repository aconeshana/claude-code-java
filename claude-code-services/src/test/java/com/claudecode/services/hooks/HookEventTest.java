package com.claudecode.services.hooks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HookEventTest {

    @Test
    void has30Events() {
        assertEquals(30, HookEvent.values().length);
    }

    @Test
    void configKeyIsLowercase() {
        assertEquals("pre_tool_use", HookEvent.PRE_TOOL_USE.configKey());
        assertEquals("session_start", HookEvent.SESSION_START.configKey());
        assertEquals("file_changed", HookEvent.FILE_CHANGED.configKey());
    }

    @Test
    void fromConfigKeyRoundTrips() {
        for (HookEvent event : HookEvent.values()) {
            assertEquals(event, HookEvent.fromConfigKey(event.configKey()));
        }
    }

    @Test
    void fromConfigKeyIsCaseInsensitive() {
        assertEquals(HookEvent.PRE_TOOL_USE, HookEvent.fromConfigKey("PRE_TOOL_USE"));
        assertEquals(HookEvent.PRE_TOOL_USE, HookEvent.fromConfigKey("pre_tool_use"));
    }

    @Test
    void fromConfigKeyAcceptsPascalCase() {

        assertEquals(HookEvent.PRE_TOOL_USE,        HookEvent.fromConfigKey("PreToolUse"));
        assertEquals(HookEvent.POST_TOOL_USE,       HookEvent.fromConfigKey("PostToolUse"));
        assertEquals(HookEvent.SESSION_START,       HookEvent.fromConfigKey("SessionStart"));
        assertEquals(HookEvent.CWD_CHANGED,         HookEvent.fromConfigKey("CwdChanged"));
        assertEquals(HookEvent.INSTRUCTIONS_LOADED, HookEvent.fromConfigKey("InstructionsLoaded"));
        assertEquals(HookEvent.POST_TOOL_BATCH, HookEvent.fromConfigKey("PostToolBatch"));
        assertEquals(HookEvent.USER_PROMPT_EXPANSION,
            HookEvent.fromConfigKey("UserPromptExpansion"));
        assertEquals(HookEvent.MESSAGE_DISPLAY, HookEvent.fromConfigKey("MessageDisplay"));
    }

    @Test
    void displayNameIsPascalCase() {
        assertEquals("PreToolUse",        HookEvent.PRE_TOOL_USE.displayName());
        assertEquals("PostToolUse",       HookEvent.POST_TOOL_USE.displayName());
        assertEquals("SessionStart",      HookEvent.SESSION_START.displayName());
        assertEquals("CwdChanged",        HookEvent.CWD_CHANGED.displayName());
        assertEquals("FileChanged",       HookEvent.FILE_CHANGED.displayName());
        assertEquals("PostToolBatch",     HookEvent.POST_TOOL_BATCH.displayName());
        assertEquals("UserPromptExpansion", HookEvent.USER_PROMPT_EXPANSION.displayName());
        assertEquals("MessageDisplay",    HookEvent.MESSAGE_DISPLAY.displayName());
    }

    @Test
    void displayNameRoundTripsViaFromConfigKey() {
        for (HookEvent event : HookEvent.values()) {
            assertEquals(event, HookEvent.fromConfigKey(event.displayName()),
                "displayName() → fromConfigKey() should round-trip for " + event);
        }
    }

    @Test
    void fromConfigKeyThrowsForUnknown() {
        assertThrows(IllegalArgumentException.class, () -> HookEvent.fromConfigKey("unknown"));
    }
}
