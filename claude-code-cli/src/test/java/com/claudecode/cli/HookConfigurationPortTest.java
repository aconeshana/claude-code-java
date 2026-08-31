package com.claudecode.cli;

import org.apache.commons.lang3.Strings;

import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEntry;
import com.claudecode.services.hooks.BashCommandHook;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.hooks.PromptHook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HookConfigurationPortTest {

    @TempDir Path tempDir;

    @Test
    void snapshotIncludesSessionAndPluginRuntimeHooks() {
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, tempDir.toString());
        engine.addExtraHooks(new HooksSettings(Map.of(
            HookEvent.STOP, List.of(new HookMatcher(Optional.empty(),
                List.of(new PromptHook("check session")))))));
        engine.setPluginHooks(Map.of(
            HookEvent.PRE_TOOL_USE, List.of(new HookMatcher(Optional.of("Bash"),
                List.of(new BashCommandHook("plugin-check"))))));

        List<HookEntry> hooks = CliRuntimeAdapters.newHookConfigurationPort(null, engine)
            .snapshot(tempDir.toString(), List.of("Bash"))
            .hooks();

        assertEquals(1, hooks.stream().filter(hook ->
            Strings.CS.equals(hook.sourceInline(), "Session")
                && Strings.CS.equals(hook.displayText(), "check session")).count());
        assertEquals(1, hooks.stream().filter(hook ->
            Strings.CS.equals(hook.sourceInline(), "Plugin")
                && Strings.CS.equals(hook.displayText(), "plugin-check")).count());
    }
}
