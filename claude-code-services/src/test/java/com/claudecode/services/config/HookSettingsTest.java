package com.claudecode.services.config;

import com.claudecode.services.hooks.BashCommandHook;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for hooks sourced from layered settings and managed policy.
 */
class HookSettingsTest {

    @TempDir
    Path tempDir;

    private final String originalCwd = System.getProperty("user.dir");

    @AfterEach
    void restoreSettingsSources() {
        SettingsSources.clearFlagSettings();
        SettingsSources.configureAllowedSettingSources(true, true, true,
            originalCwd == null ? tempDir.toString() : originalCwd);
    }

    @Test
    void goalHookRestrictionUsesEffectiveFlagsAndDefaultsToFalse() throws Exception {
        assertFalse(HookSettings.areGoalHooksRestricted(JsonUtils.getMapper().createObjectNode()));

        SettingsSources.configureAllowedSettingSources(List.of(), tempDir.toString(), false);
        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree("""
            {"disableAllHooks":false,"allowManagedHooksOnly":false}
            """));

        assertFalse(HookSettings.areGoalHooksRestricted());

        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"disableAllHooks\":true}"));
        assertTrue(HookSettings.areGoalHooksRestricted());

        SettingsSources.applyFlagSettings(JsonUtils.getMapper().readTree(
            "{\"disableAllHooks\":false,\"allowManagedHooksOnly\":true}"));
        assertTrue(HookSettings.areGoalHooksRestricted());
    }

    @Test
    void publicLoaderReadsAcceptedFlagHooksFromTheEffectiveSnapshot() throws Exception {
        SettingsSources.configureAllowedSettingSources(List.of(), tempDir.toString(), false);
        SettingsSources.applyFlagSettings(hooksSettings("flag-hook"));

        assertCommand(HookSettings.loadHooksSettings(), "flag-hook");
    }

    @Test
    void loaderPreservesManagedHookPolicyGates() {
        ObjectNode effective = hooksSettings("user-hook");
        ObjectNode policy = hooksSettings("managed-hook");

        policy.put("disableAllHooks", true);
        assertSame(HooksSettings.EMPTY, HookSettings.loadHooksSettings(effective, policy));

        policy.put("disableAllHooks", false);
        policy.put("allowManagedHooksOnly", true);
        assertCommand(HookSettings.loadHooksSettings(effective, policy), "managed-hook");

        policy.put("allowManagedHooksOnly", false);
        effective.put("disableAllHooks", true);
        assertCommand(HookSettings.loadHooksSettings(effective, policy), "managed-hook");

        effective.put("disableAllHooks", false);
        policy.put("strictPluginOnlyCustomization", true);
        assertCommand(HookSettings.loadHooksSettings(effective, policy), "managed-hook");

        policy.put("strictPluginOnlyCustomization", false);
        ArrayNode restrictedSurfaces = policy.putArray("strictPluginOnlyCustomization");
        restrictedSurfaces.add("hooks");
        assertCommand(HookSettings.loadHooksSettings(effective, policy), "managed-hook");
    }

    @Test
    void loaderUsesMergedHooksWhenNoPolicyGateAppliesAndDefaultsToEmpty() {
        ObjectNode effective = hooksSettings("merged-hook");
        ObjectNode policy = JsonUtils.getMapper().createObjectNode();

        assertCommand(HookSettings.loadHooksSettings(effective, policy), "merged-hook");
        assertSame(HooksSettings.EMPTY, HookSettings.loadHooksSettings(
            JsonUtils.getMapper().createObjectNode(), policy));
        assertSame(HooksSettings.EMPTY, HookSettings.loadHooksSettings(
            JsonUtils.getMapper().createObjectNode().putObject("hooks"), policy));
    }

    private static ObjectNode hooksSettings(String command) {
        ObjectNode settings = JsonUtils.getMapper().createObjectNode();
        ArrayNode stop = settings.putObject("hooks").putArray("Stop");
        ObjectNode matcher = stop.addObject();
        matcher.putArray("hooks").addObject()
            .put("type", "command")
            .put("command", command);
        return settings;
    }

    private static void assertCommand(HooksSettings settings, String command) {
        BashCommandHook hook = (BashCommandHook) settings.getMatchers(HookEvent.STOP)
            .getFirst().hooks().getFirst();
        assertEquals(command, hook.command());
    }
}
