package com.claudecode.services.hooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HookEngine#setPluginHooks} — the plugin hook channel must be
 * consulted alongside settings hooks, swapped atomically, and stay
 * independent of {@link HookEngine#replaceSettings}.
 */
class HookEnginePluginHooksTest {

    private static HookMatcher echoMatcher(String pattern, String text) {
        return new HookMatcher(
            pattern == null ? Optional.empty() : Optional.of(pattern),
            List.of(new BashCommandHook("echo " + text)));
    }

    @Test
    void pluginHooksFireAlongsideSettingsHooks() {
        HooksSettings settings = new HooksSettings(Map.of(
            HookEvent.SESSION_START, List.of(echoMatcher(null, "from-settings"))));
        HookEngine engine = new HookEngine(settings, "/tmp");
        engine.setPluginHooks(Map.of(
            HookEvent.SESSION_START, List.of(echoMatcher(null, "from-plugin"))));

        List<HookResult> results = engine.executeHooks(
            HookEvent.SESSION_START, HookInput.forSessionStart("startup"));
        assertEquals(2, results.size(), "settings hook + plugin hook must both run");
    }

    @Test
    void pluginHooksMatchOnToolName() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setPluginHooks(Map.of(
            HookEvent.PRE_TOOL_USE, List.of(echoMatcher("Bash", "matched"))));

        var input = new ObjectMapper().createObjectNode();
        assertEquals(1, engine.executeHooks(HookEvent.PRE_TOOL_USE,
            HookInput.forPreToolUse("Bash", input, "tu-1")).size());
        assertTrue(engine.executeHooks(HookEvent.PRE_TOOL_USE,
            HookInput.forPreToolUse("Read", input, "tu-2")).isEmpty());
    }

    @Test
    void setPluginHooksReplacesPreviousGenerationWholesale() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setPluginHooks(Map.of(
            HookEvent.SESSION_START, List.of(echoMatcher(null, "old"))));
        engine.setPluginHooks(Map.of(
            HookEvent.STOP, List.of(echoMatcher(null, "new"))));

        assertTrue(engine.executeHooks(HookEvent.SESSION_START,
                HookInput.forSessionStart("startup")).isEmpty(),
            "old generation must be gone after the swap");
        assertTrue(engine.currentPluginHooks().containsKey(HookEvent.STOP));
    }

    @Test
    void nullClearsPluginHooks() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setPluginHooks(Map.of(
            HookEvent.SESSION_START, List.of(echoMatcher(null, "x"))));
        engine.setPluginHooks(null);

        assertTrue(engine.currentPluginHooks().isEmpty());
        assertTrue(engine.executeHooks(HookEvent.SESSION_START,
            HookInput.forSessionStart("startup")).isEmpty());
    }

    @Test
    void replaceSettingsDoesNotTouchPluginHooks() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, "/tmp");
        engine.setPluginHooks(Map.of(
            HookEvent.SESSION_START, List.of(echoMatcher(null, "plugin"))));

        engine.replaceSettings(HooksSettings.EMPTY);
        assertEquals(1, engine.executeHooks(HookEvent.SESSION_START,
                HookInput.forSessionStart("startup")).size(),
            "settings hot-reload must not wipe the plugin hook channel");
    }
}
