package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.apache.commons.lang3.Strings;


import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class UiSettingsPolicyTest {

    @AfterEach
    void restoreFallbackBackend() {
        UiSettings.configure(null);
    }

    @Test
    void disableAllHooksByPolicy_defaultsFalseWhenNoPolicyFile() {
        assertFalse(UiSettings.readDisableAllHooksByPolicy());
    }

    @Test
    void allowManagedHooksOnlyByPolicy_defaultsFalseWhenNoPolicyFile() {
        assertFalse(UiSettings.readAllowManagedHooksOnlyByPolicy());
    }

    @Test
    void claudeHudPrefersEffectiveSettingsValue() {
        UiSettings.configure(backend(JsonUtils.getMapper()
            .getNodeFactory().booleanNode(false), true));

        assertFalse(UiSettings.isClaudeHudEnabled());
    }

    @Test
    void claudeHudFallsBackToLegacyGlobalValueWhenSettingIsAbsent() {
        UiSettings.configure(backend(null, true));

        assertTrue(UiSettings.isClaudeHudEnabled());
    }

    @Test
    void claudeHudPreservesLegacyExplicitFalse() {
        UiSettings.configure(backend(null, false));

        assertFalse(UiSettings.isClaudeHudEnabled());
    }

    @Test
    void claudeHudDefaultsEnabledWhenNeitherKeyExists() {
        UiSettings.configure(backend(null, null));

        assertTrue(UiSettings.isClaudeHudEnabled());
    }

    private static UiSettings.Backend backend(JsonNode effectiveHud, Boolean legacyHud) {
        return new UiSettings.Backend() {
            @Override public boolean globalBoolean(String key, boolean defaultValue) {
                return Strings.CS.equals("claudeHudEnabled", key) && legacyHud != null
                    ? legacyHud : defaultValue;
            }
            @Override public String globalString(String key, String defaultValue) { return defaultValue; }
            @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
            @Override public JsonNode effectiveSetting(String key) {
                return Strings.CS.equals("claudeHudEnabled", key) ? effectiveHud : null;
            }
            @Override public void setGlobal(String key, Object value) { }
            @Override public boolean spinnerTipsEnabled() { return true; }
            @Override public boolean prefersReducedMotion() { return false; }
            @Override public Boolean policyBoolean(String key) { return null; }
            @Override public SandboxConfig sandboxConfig() { return SandboxConfig.disabled(); }
            @Override public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                                     String ruleString, RuleSource tier) { }
            @Override public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                                        String ruleString, RuleSource tier) { }
            @Override public void persistPermissionUpdate(String cwd, PermissionUpdate update) { }
        };
    }
}
