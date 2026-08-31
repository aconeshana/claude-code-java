package com.claudecode.services.hooks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HooksConfigManagerCatalogTest {

    @Test
    void exposes197MetadataForNewHookEvents() {
        Map<HookEvent, HookEventMetadata> metadata =
            HooksConfigManager.getHookEventMetadata(List.of("Bash"));

        assertEquals(HookEvent.values().length, metadata.size());

        HookEventMetadata batch = metadata.get(HookEvent.POST_TOOL_BATCH);
        assertEquals("After a batch of tool calls resolves", batch.summary());
        assertNull(batch.matcherMetadata());

        HookEventMetadata expansion = metadata.get(HookEvent.USER_PROMPT_EXPANSION);
        assertEquals("When a user-typed slash command expands into a prompt", expansion.summary());
        assertEquals("command_name", expansion.matcherMetadata().matcherType());

        HookEventMetadata display = metadata.get(HookEvent.MESSAGE_DISPLAY);
        assertEquals("While assistant message text is displayed", display.summary());
        assertNull(display.matcherMetadata());
    }
}
