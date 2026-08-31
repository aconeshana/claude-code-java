package com.claudecode.services.config;

import com.claudecode.permissions.RuleSource;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.hooks.HttpHookPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Strings;

/**
 * Resolves the effective settings-file hook configuration and hook policy gates.
 */
public final class HookSettings {

    private HookSettings() {}


    public static boolean areGoalHooksRestricted() {
        return areGoalHooksRestricted(effectiveSettings(currentCwd()));
    }

    /** Package-private pure effective-settings core for settings-source tests. */
    static boolean areGoalHooksRestricted(ObjectNode effective) {
        return Boolean.TRUE.equals(SettingsTreeReader.booleanValue(effective, "disableAllHooks"))
            || Boolean.TRUE.equals(SettingsTreeReader.booleanValue(
                effective, "allowManagedHooksOnly"));
    }

    /**
     * Loads the merged hook configuration from all enabled settings sources, including the
     * CLI/SDK flag source and managed policy.
     */
    public static HooksSettings loadHooksSettings() {
        String cwd = currentCwd();
        ObjectNode effective = effectiveSettings(cwd);
        ObjectNode policy = SettingsSources.settingsForSource(RuleSource.POLICY_SETTINGS, cwd);
        return loadHooksSettings(effective, policy);
    }

    /** Loads the effective HTTP-hook policy from the same settings snapshot as hooks. */
    public static HttpHookPolicy loadHttpHookPolicy() {
        return loadHttpHookPolicy(effectiveSettings(currentCwd()));
    }

    /** Package-private pure seam for policy precedence/absence tests. */
    static HttpHookPolicy loadHttpHookPolicy(ObjectNode effective) {
        return new HttpHookPolicy(
            optionalStringArray(effective, "allowedHttpHookUrls"),
            optionalStringArray(effective, "httpHookAllowedEnvVars"));
    }

    /** Package-private pure policy core for settings-source tests. */
    static HooksSettings loadHooksSettings(ObjectNode effective, ObjectNode policy) {
        if (Boolean.TRUE.equals(SettingsTreeReader.booleanValue(policy, "disableAllHooks"))) {
            return HooksSettings.EMPTY;
        }
        if (Boolean.TRUE.equals(SettingsTreeReader.booleanValue(policy, "allowManagedHooksOnly"))
                || policyRestrictsHooks(policy)) {
            return hooksFrom(policy);
        }
        if (Boolean.TRUE.equals(SettingsTreeReader.booleanValue(effective, "disableAllHooks"))) {
            return hooksFrom(policy);
        }
        return hooksFrom(effective);
    }

    /**
     * Package-private strict single-file seam for reload characterization tests. Missing files
     * are ordinary; a present malformed file is surfaced so the caller can retain the previous
     * hook snapshot rather than treating a partial external edit as an empty configuration.
     */
    static HooksSettings loadHooksSettings(Path settingsPath) {
        if (!Files.exists(settingsPath)) return HooksSettings.EMPTY;
        try {
            ObjectNode accepted = SettingsTreeReader.accepted(
                SettingsTreeReader.readCached(settingsPath, true));
            return accepted == null ? HooksSettings.EMPTY : hooksFrom(accepted);
        } catch (IOException | RuntimeException e) {
            throw new SettingsParseException(settingsPath, e);
        }
    }

    private static ObjectNode effectiveSettings(String cwd) {
        return SettingsSnapshots.effective(cwd);
    }

    private static String currentCwd() {
        return SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
    }

    private static HooksSettings hooksFrom(ObjectNode settings) {
        JsonNode hooks = SettingsTreeReader.objectValue(settings, "hooks");
        return hooks == null || hooks.isEmpty() ? HooksSettings.EMPTY : HooksSettings.fromJson(hooks);
    }

/**
     * Absent or non-array setting → {@code null}.
     */
    private static List<String> optionalStringArray(
            ObjectNode settings, String field) {
        if (settings == null || !settings.has(field)) {
            return null;
        }
        JsonNode value = settings.get(field);
        if (!value.isArray()) return null;
        List<String> values = new ArrayList<>();
        value.forEach(node -> {
            if (node.isTextual()) values.add(node.asText());
        });
        return List.copyOf(values);
    }

    private static boolean policyRestrictsHooks(ObjectNode policy) {
        if (policy == null) return false;
        JsonNode restriction = policy.get("strictPluginOnlyCustomization");
        if (restriction == null) return false;
        if (restriction.isBoolean()) return restriction.asBoolean();
        if (!restriction.isArray()) return false;
        for (JsonNode surface : restriction) {
            if (surface.isTextual() &&Strings.CS.equals( "hooks", surface.asText())) return true;
        }
        return false;
    }
}
