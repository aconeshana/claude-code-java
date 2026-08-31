package com.claudecode.services.config;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.Strings;

/**
 * Reads and persists permission-specific settings without coupling consumers to the legacy
 * all-settings facade.
 */
public final class PermissionSettings {

    private static final List<PermissionBehavior> RULE_BEHAVIORS = List.of(
        PermissionBehavior.ALLOW, PermissionBehavior.DENY, PermissionBehavior.ASK);

    private PermissionSettings() {}

    /**
     * Loads permission rules in the configured source order, retaining every source label even
     * when two enabled editable sources resolve to the same physical settings file.
     *
     * @throws SettingsParseException when an enabled editable settings file exists but cannot be
     *     parsed as strict JSON
     */
    public static List<PermissionRule> loadPermissionRules(String cwd) {
        return loadPermissionRules(cwd,
            SettingsSources.settingsForSource(RuleSource.POLICY_SETTINGS, cwd));
    }


    static List<PermissionRule> loadPermissionRulesForReload(String cwd) {
        JsonNode policySettings = SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS, cwd);
        if (allowsManagedPermissionRulesOnly(policySettings)) {
            return permissionRulesFrom(policySettings, RuleSource.POLICY_SETTINGS);
        }

        List<PermissionRule> rules = new ArrayList<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            switch (source) {
                case FLAG_SETTINGS, POLICY_SETTINGS -> addPermissionRules(rules,
                    SettingsSources.settingsForSource(source, cwd), source);
                case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> {
                    ObjectNode settings = SettingsSources.settingsForSource(source, cwd);
                    if (settings != null) addPermissionRules(rules, settings, source);
                }
                default -> throw new IllegalStateException(
                    "Unexpected configured settings source: " + source);
            }
        }
        return List.copyOf(rules);
    }


    public static List<PermissionRule> loadPermissionRulesForExecution(String cwd) {
        return loadPermissionRulesForReload(cwd);
    }

    /**
     * Reads strict, schema-accepted permission rules from one editable settings file. Missing
     * files and disabled paths contribute no rules; a present malformed file fails closed so a
     * caller can retain its prior execution state.
     *
     * @throws SettingsParseException when the file exists but cannot be parsed
     */
    public static List<PermissionRule> loadPermissionRulesFromFile(Path settingsPath,
                                                                     RuleSource source) {
        if (!Files.exists(settingsPath) || SettingsSources.isReadPathDisabled(settingsPath)) {
            return List.of();
        }
        try {
            ObjectNode settings = SettingsTreeReader.accepted(
                SettingsTreeReader.readCached(settingsPath, true));
            return permissionRulesFrom(settings, source);
        } catch (IOException | RuntimeException e) {
            throw new SettingsParseException(settingsPath, e);
        }
    }

    /** Returns whether managed policy suppresses every non-policy permission-rule source. */
    public static boolean shouldAllowManagedPermissionRulesOnly() {
        return allowsManagedPermissionRulesOnly(SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS, currentCwd()));
    }

    /** Returns the effective persisted permission mode, or {@code null} when it is unset. */
    public static String loadDefaultPermissionMode() {
        return SettingsTreeReader.nestedStringValue(
            SettingsSnapshots.effective(currentCwd()), "permissions", "defaultMode");
    }

    /**
     * Returns whether the effective settings prohibit bypass-permissions mode.
     */
    public static boolean isBypassPermissionsModeDisabled() {
        return Strings.CS.equals( "disable", SettingsTreeReader.nestedStringValue(
            SettingsSnapshots.effective(currentCwd()), "permissions",
            "disableBypassPermissionsMode"));
    }

    /**
     * Returns whether a trusted source has acknowledged the bypass-permissions warning. Project
     * settings are deliberately excluded because repository-controlled content cannot suppress a
     * safety prompt.
     */
    public static boolean hasSkipDangerousModePermissionPrompt() {
        return trustedBooleanSetting("skipDangerousModePermissionPrompt", List.of(
            RuleSource.USER_SETTINGS,
            RuleSource.LOCAL_SETTINGS,
            RuleSource.FLAG_SETTINGS,
            RuleSource.POLICY_SETTINGS));
    }

    /**
     * Returns whether a trusted source has acknowledged the auto-mode warning.
     */
    public static boolean hasSkipAutoPermissionPrompt() {
        return trustedBooleanSetting("skipAutoPermissionPrompt", List.of(
            RuleSource.POLICY_SETTINGS,
            RuleSource.USER_SETTINGS,
            RuleSource.LOCAL_SETTINGS,
            RuleSource.FLAG_SETTINGS));
    }




    public static boolean useAutoModeDuringPlan() {
        String cwd = currentCwd();
        for (RuleSource source : List.of(
                RuleSource.POLICY_SETTINGS,
                RuleSource.FLAG_SETTINGS,
                RuleSource.USER_SETTINGS,
                RuleSource.LOCAL_SETTINGS)) {
            if (Boolean.FALSE.equals(SettingsTreeReader.booleanValue(
                    SettingsSources.settingsForSource(source, cwd),
                    "useAutoModeDuringPlan"))) {
                return false;
            }
        }
        return true;
    }


    public static boolean isAutoModeGateEnabledBySettings() {
        String cwd = currentCwd();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            JsonNode settings = SettingsSources.settingsForSource(source, cwd);
            if (Strings.CS.equals("disable", settings.path("disableAutoMode").asText(null))) {
                return false;
            }
            JsonNode permissions = SettingsTreeReader.objectValue(settings, "permissions");
            if (permissions != null && Strings.CS.equals(
                    "disable", permissions.path("disableAutoMode").asText(null))) {
                return false;
            }
        }
        return true;
    }

    /** Persists the bypass-permissions acknowledgement in the user settings tier. */
    public static void saveSkipDangerousModePermissionPrompt() {
        SettingsEditor.writeUserBoolean("skipDangerousModePermissionPrompt", true);
    }

    /** Persists the default permission mode in the user settings tier. */
    public static void saveDefaultPermissionMode(String mode) {
        SettingsEditor.writeDefaultPermissionMode(currentCwd(), mode, RuleSource.USER_SETTINGS);
    }

    /**
     * Returns the ordered, de-duplicated union of configured additional directories from enabled
     * settings sources.
     */
    public static List<String> loadAdditionalDirectories(String cwd) {
        List<String> directories = new ArrayList<>();
        Set<String> seenDirectories = new HashSet<>();
        Set<Path> seenEditablePaths = new HashSet<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            switch (source) {
                case FLAG_SETTINGS, POLICY_SETTINGS ->
                    addAdditionalDirectories(directories, seenDirectories,
                        SettingsSources.settingsForSource(source, cwd));
                case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> {
                    Path path = SettingsSources.editablePath(source, cwd);
                    Path normalized = path.toAbsolutePath().normalize();
                    if (seenEditablePaths.add(normalized)) {
                        addAdditionalDirectories(directories, seenDirectories,
                            SettingsTreeReader.readAccepted(path, true));
                    }
                }
                default -> throw new IllegalStateException(
                    "Unexpected configured settings source: " + source);
            }
        }
        return List.copyOf(directories);
    }

    /** Persists one remembered additional directory in local settings for the {@code /add-dir} flow. */
    public static void saveAdditionalDirectoryToLocalSettings(String cwd, String absolutePath) {
        SettingsEditor.addAdditionalDirectoryToLocalSettings(cwd, absolutePath);
    }

    /** Adds a normalized permission rule to an editable settings destination. */
    public static void addPermissionRule(String cwd, PermissionBehavior behavior, String rule,
                                         RuleSource tier) {

        // policy explicitly owns the permission rule set. Keep the guard at this
        // public facade so UI, SDK, and command callers share the same safety boundary.
        if (shouldAllowManagedPermissionRulesOnly()) return;
        SettingsEditor.addPermissionRule(cwd, behavior, rule, tier);
    }

    /** Removes all normalized equivalents of a rule from an editable settings destination. */
    public static void removePermissionRule(String cwd, PermissionBehavior behavior, String rule,
                                            RuleSource tier) {
        SettingsEditor.removePermissionRule(cwd, behavior, rule, tier);
    }


    public static void removePermissionRuleForUpdate(String cwd, PermissionBehavior behavior,
                                                     String rule, RuleSource tier) {
        SettingsEditor.removePermissionRuleForUpdate(cwd, behavior, rule, tier);
    }

    /** Replaces one permission behavior's rule array in an editable settings destination. */
    public static void replacePermissionRules(String cwd, PermissionBehavior behavior,
                                              List<String> rules, RuleSource tier) {
        SettingsEditor.replacePermissionRules(cwd, behavior, rules, tier);
    }

    /** Persists a permission mode in its requested editable destination. */
    public static void saveDefaultPermissionMode(String cwd, String mode, RuleSource tier) {
        SettingsEditor.writeDefaultPermissionMode(cwd, mode, tier);
    }

    /** Appends unseen directories to an editable settings destination. */
    public static void addAdditionalDirectories(String cwd, List<String> directories,
                                                RuleSource tier) {
        SettingsEditor.addAdditionalDirectories(cwd, directories, tier);
    }

    /** Removes directories from an editable settings destination. */
    public static void removeAdditionalDirectories(String cwd, List<String> directories,
                                                   RuleSource tier) {
        SettingsEditor.removeAdditionalDirectories(cwd, directories, tier);
    }

    /** Test seam for deterministic tier precedence without changing process-global source state. */
    static String loadDefaultPermissionMode(List<Path> tiers) {
        String mode = null;
        for (Path tier : tiers) {
            String value = SettingsTreeReader.nestedStringValue(
                SettingsTreeReader.readAccepted(tier, false), "permissions", "defaultMode");
            if (value != null) mode = value;
        }
        return mode;
    }

    /** Test seam for policy-only rule behavior without requiring a host-managed settings file. */
    static List<PermissionRule> loadPermissionRules(String cwd, JsonNode policySettings) {
        if (allowsManagedPermissionRulesOnly(policySettings)) {
            return permissionRulesFrom(policySettings, RuleSource.POLICY_SETTINGS);
        }

        List<PermissionRule> rules = new ArrayList<>();
        for (RuleSource source : SettingsSources.enabledOrder()) {
            switch (source) {
                case FLAG_SETTINGS -> addPermissionRules(rules,
                    SettingsSources.settingsForSource(source, cwd), source);
                case POLICY_SETTINGS -> addPermissionRules(rules, policySettings, source);
                case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> rules.addAll(
                    loadPermissionRulesFromFile(SettingsSources.editablePath(source, cwd), source));
                default -> throw new IllegalStateException(
                    "Unexpected configured settings source: " + source);
            }
        }
        return List.copyOf(rules);
    }

    /** Package-private policy core for isolated managed-settings tests. */
    static boolean allowsManagedPermissionRulesOnly(JsonNode policySettings) {
        return Boolean.TRUE.equals(SettingsTreeReader.booleanValue(
            policySettings, "allowManagedPermissionRulesOnly"));
    }

    private static List<PermissionRule> permissionRulesFrom(JsonNode settings, RuleSource source) {
        List<PermissionRule> rules = new ArrayList<>();
        addPermissionRules(rules, settings, source);
        return List.copyOf(rules);
    }

    private static void addPermissionRules(List<PermissionRule> out, JsonNode settings,
                                           RuleSource source) {
        JsonNode permissions = SettingsTreeReader.objectValue(settings, "permissions");
        if (permissions == null) return;
        for (PermissionBehavior behavior : RULE_BEHAVIORS) {
            JsonNode entries = permissions.get(arrayKey(behavior));
            if (entries == null || !entries.isArray()) continue;
            for (JsonNode entry : entries) {
                if (entry.isTextual()
                        && PermissionRuleValidation.validatePermissionRule(entry.asText()).valid()) {
                    out.add(PermissionEngine.permissionRuleFromString(
                        entry.asText(), behavior, source));
                }
            }
        }
    }

    private static void addAdditionalDirectories(List<String> out, Set<String> seen,
                                                 JsonNode settings) {
        JsonNode permissions = SettingsTreeReader.objectValue(settings, "permissions");
        if (permissions == null) return;
        JsonNode configured = permissions.get("additionalDirectories");
        if (configured == null || !configured.isArray()) return;
        for (JsonNode directory : configured) {
            if (directory.isTextual() && seen.add(directory.asText())) {
                out.add(directory.asText());
            }
        }
    }

    private static boolean trustedBooleanSetting(String key, List<RuleSource> sources) {
        String cwd = currentCwd();
        for (RuleSource source : sources) {
            if (Boolean.TRUE.equals(SettingsTreeReader.booleanValue(
                    SettingsSources.settingsForSource(source, cwd), key))) {
                return true;
            }
        }
        return false;
    }

    private static String arrayKey(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
            case PASSTHROUGH -> throw new IllegalArgumentException(
                "PASSTHROUGH is not a persisted permission behavior");
        };
    }

    private static String currentCwd() {
        return SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
    }
}
