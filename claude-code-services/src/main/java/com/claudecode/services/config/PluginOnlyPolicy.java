package com.claudecode.services.config;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.permissions.RuleSource;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/**
 * Runtime enforcement helpers for policy {@code strictPluginOnlyCustomization}.
 */
public final class PluginOnlyPolicy {
    private static final Set<String> ADMIN_TRUSTED_SOURCES = Set.of(
        "plugin", "policySettings", "built-in", "builtin", "bundled");

    private PluginOnlyPolicy() {}

    /** Returns whether the managed policy locks the requested customization surface. */
    public static boolean isRestrictedToPluginOnly(String surface) {
        if (StringUtils.isBlank(surface)) return false;
        JsonNode policy = SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS,
            SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString());
        JsonNode restriction = policy.get("strictPluginOnlyCustomization");
        if (restriction == null) return false;
        if (restriction.isBoolean()) return restriction.asBoolean();
        if (!restriction.isArray()) return false;
        for (JsonNode value : restriction) {
            if (value.isTextual() && surface.equals(value.asText())) return true;
        }
        return false;
    }

    /** Whether an item source bypasses plugin-only restrictions. */
    public static boolean isSourceAdminTrusted(String source) {
        return source != null && ADMIN_TRUSTED_SOURCES.contains(source);
    }
}
