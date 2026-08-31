package com.claudecode.services.config;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.permissions.RuleSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Runtime-facing scalar access to the effective settings snapshot.
 */
public final class RuntimeSettings {

    private RuntimeSettings() {}

    /** Reads the machine-local auto-compact preference, defaulting to enabled. */
    public static boolean loadAutoCompactEnabled() {
        return GlobalConfigStore.getBoolean("autoCompactEnabled", true);
    }


    public static long loadMessageIdleNotifThresholdMs() {
        return Math.max(1, GlobalConfigStore.getInt("messageIdleNotifThresholdMs", 60_000));
    }

    /** Opt-in for provider-error-triggered compaction recovery. */
    @Explanation("Opt-in bridge for ant-only REACTIVE_COMPACT")
    public static boolean loadReactiveCompactEnabled() {
        return booleanSetting("reactiveCompactEnabled", false);
    }

    /** Reads the effective extended-thinking preference, defaulting to enabled. */
    public static boolean loadAlwaysThinkingEnabled() {
        return booleanSetting("alwaysThinkingEnabled", true);
    }

    /** Reads the persisted Fast Mode preference; absent settings default to off. */
    public static boolean loadFastModeEnabled() {
        return booleanSetting("fastMode", false);
    }

    /** Whether Fast Mode must be explicitly enabled for every new session. */
    public static boolean loadFastModePerSessionOptIn() {
        return booleanSetting("fastModePerSessionOptIn", false);
    }

    



    public static boolean loadSwitchModelsOnFlagEnabled() {
        return booleanSetting("switchModelsOnFlag", true);
    }

    /** Reads the effective reduced-motion preference, defaulting to disabled. */
    public static boolean loadPrefersReducedMotion() {
        return booleanSetting("prefersReducedMotion", false);
    }

    /** Reads the effective spinner-tips preference, defaulting to enabled. */
    public static boolean loadSpinnerTipsEnabled() {
        return booleanSetting("spinnerTipsEnabled", true);
    }

    /** Reads the effective syntax-highlighting preference, defaulting to enabled. */
    public static boolean loadSyntaxHighlightingDisabled() {
        return booleanSetting("syntaxHighlightingDisabled", false);
    }

    /** Returns a detached value from the current effective settings snapshot. */
    public static JsonNode loadEffectiveSetting(String key) {
        JsonNode value = effectiveValue(key);
        return value == null ? null : value.deepCopy();
    }

    /**
     * Returns an explicitly configured effective boolean, or {@code null} when the key is absent
     * or is not a boolean.  Callers with a domain default should apply it at their own boundary.
     */
    public static Boolean loadOptionalBoolean(String key) {
        return optionalBooleanSetting(key);
    }

    /**
     * Returns an explicitly configured managed-policy boolean without consulting editable or
     * flag settings.  This is intentionally nullable so policy gates can distinguish an absent
     * key from an explicit {@code false}.
     */
    public static Boolean readPolicyBoolean(String key) {
        if (StringUtils.isBlank(key)) return null;
        return SettingsTreeReader.booleanValue(SettingsSources.settingsForSource(
            RuleSource.POLICY_SETTINGS, currentCwd()), key);
    }

    /** Reads one untrimmed string from the user source only. */
    public static String loadUserSettingString(String key) {
        if (StringUtils.isBlank(key)) return null;
        return SettingsTreeReader.stringValue(
            SettingsSources.settingsForSource(RuleSource.USER_SETTINGS, currentCwd()), key, false);
    }

    /** Reads the WebFetch preflight bypass switch, defaulting to disabled. */
    public static boolean loadSkipWebFetchPreflight() {
        return booleanSetting("skipWebFetchPreflight", false);
    }

    /** Persists the syntax-highlighting switch in the user settings tier. */
    public static void saveSyntaxHighlightingDisabled(boolean disabled) {
        SettingsEditor.writeUserBoolean("syntaxHighlightingDisabled", disabled);
    }

    /** Reads the effective auto-memory switch, defaulting to enabled. */
    public static boolean loadAutoMemoryEnabled() {
        return booleanSetting("autoMemoryEnabled", true);
    }

    /** Persists the auto-memory switch in the user settings tier. */
    public static void saveAutoMemoryEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("autoMemoryEnabled", enabled);
    }


    public static boolean isAutoDreamEnabled() {
        return AutoDreamFeatureGate.enabled(optionalBooleanSetting("autoDreamEnabled"));
    }

    /** Persists the auto-dream switch in the user settings tier. */
    public static void saveAutoDreamEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("autoDreamEnabled", enabled);
    }

    /** Reads the released Session recap switch, defaulting to enabled. */
    public static boolean loadAwaySummaryEnabled() {
        return booleanSetting("awaySummaryEnabled", true);
    }

    /** Reads the Java runtime's agent-progress-summary switch, defaulting to disabled. */
    public static boolean loadAgentProgressSummariesEnabled() {
        return booleanSetting("agentProgressSummariesEnabled", false);
    }

    /** Reads the Java runtime's memory-extraction switch, defaulting to disabled. */
    public static boolean loadExtractMemoriesEnabled() {
        return booleanSetting("extractMemoriesEnabled", false);
    }

    /** Reads the Java runtime's team-memory safety switch, defaulting to disabled. */
    public static boolean loadTeamMemoryEnabled() {
        return booleanSetting("teamMemoryEnabled", false);
    }

    /** Reads session cleanup retention in days, defaulting to thirty days. */
    public static int loadCleanupPeriodDays() {
        return integerSetting("cleanupPeriodDays", 30);
    }

    /**
     * User-global maximum ordinary sub-agent nesting depth.
     */
    @Explanation("Makes the Java depth cap configurable while retaining a default of five")
    public static int loadSubagentMaxDepth() {
        JsonNode user = SettingsSources.settingsForSource(
            RuleSource.USER_SETTINGS, currentCwd()).get("subagentMaxDepth");
        Integer value = user != null && user.isIntegralNumber() ? user.asInt() : null;
        return value != null && value >= 1 && value <= 5 ? value : 2;
    }

    /** Persists the user-global ordinary sub-agent nesting depth. */
    public static void saveSubagentMaxDepth(int maxDepth) {
        if (maxDepth < 1 || maxDepth > 5) {
            throw new IllegalArgumentException("subagentMaxDepth must be between 1 and 5");
        }
        SettingsEditor.writeUserValue("subagentMaxDepth", maxDepth);
    }

    /** Reads the Java runtime's time-based microcompact switch, defaulting to disabled. */
    public static boolean loadTimeBasedMicrocompactEnabled() {
        return booleanSetting("timeBasedMicrocompactEnabled", false);
    }

    /** Reads the time-based microcompact gap in minutes, defaulting to sixty. */
    public static int loadTimeBasedMicrocompactGapMinutes() {
        return integerSetting("timeBasedMicrocompactGapMinutes", 60);
    }

    /** Reads the number of recent messages time-based microcompact keeps, defaulting to five. */
    public static int loadTimeBasedMicrocompactKeepRecent() {
        return integerSetting("timeBasedMicrocompactKeepRecent", 5);
    }

    /** Returns the effective language preference, preserving surrounding whitespace. */
    public static String loadLanguage() {
        return stringSetting("language");
    }

    /** Returns the effective output-style name, or {@code null} when absent. */
    public static String loadOutputStyleName() {
        return stringSetting("outputStyle");
    }

    /** Returns a schema-accepted persisted reasoning effort, or {@code null}. */
    public static String loadEffortLevel() {
        String value = stringSetting("effortLevel");
        if (value == null) return null;
        return isAllowedPersistedEffort(value) ? value : null;
    }

    /** Returns the Java runtime's AskUserQuestion preview-format extension, or {@code null}. */
    public static String loadAskUserQuestionPreviewFormat() {
        return stringSetting("askUserQuestion.previewFormat");
    }

    /** Persists the extended-thinking switch in the user settings tier. */
    public static void saveAlwaysThinkingEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("alwaysThinkingEnabled", enabled);
    }

    /** Persists the Fast Mode preference in the user settings tier. */
    public static void saveFastModeEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("fastMode", enabled);
    }

    /**
     * Persists the refusal-fallback switch in the user settings tier, which is the tier.
     */
    public static void saveSwitchModelsOnFlagEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("switchModelsOnFlag", enabled);
    }

    /** Persists the spinner-tips switch in the local settings tier. */
    public static void saveSpinnerTipsEnabled(boolean enabled) {
        SettingsEditor.writeLocalBoolean(currentCwd(), "spinnerTipsEnabled", enabled);
    }

    /** Persists the reduced-motion switch in the local settings tier. */
    public static void savePrefersReducedMotion(boolean enabled) {
        SettingsEditor.writeLocalBoolean(currentCwd(), "prefersReducedMotion", enabled);
    }

    /** Persists the native HUD switch in the user settings tier. */
    public static void saveClaudeHudEnabled(boolean enabled) {
        SettingsEditor.writeUserBoolean("claudeHudEnabled", enabled);
    }

    /** Test seam for scalar precedence across injected file tiers; later tiers win. */
    static boolean loadLayeredBoolean(String key, boolean defaultValue, List<Path> tiers) {
        Boolean value = loadOptionalBoolean(key, tiers);
        return value == null ? defaultValue : value;
    }

    /** Test seam preserving absence separately from an explicit effective boolean. */
    static Boolean loadOptionalBoolean(String key, List<Path> tiers) {
        if (StringUtils.isBlank(key) || tiers == null) return null;
        Boolean result = null;
        for (Path tier : tiers) {
            Boolean candidate = SettingsTreeReader.booleanValue(
                SettingsTreeReader.readAccepted(tier, false), key);
            if (candidate != null) result = candidate;
        }
        return result;
    }

    /** Test seam for integer precedence across injected file tiers; later tiers win. */
    static int loadLayeredInt(String key, int defaultValue, List<Path> tiers) {
        if (StringUtils.isBlank(key) || tiers == null) return defaultValue;
        int result = defaultValue;
        for (Path tier : tiers) {
            Integer candidate = SettingsTreeReader.integerValue(
                SettingsTreeReader.readAccepted(tier, false), key);
            if (candidate != null) result = candidate;
        }
        return result;
    }

    /** Test seam for string precedence across injected file tiers; later tiers win. */
    static String loadLayeredString(String key, List<Path> tiers) {
        if (StringUtils.isBlank(key) || tiers == null) return null;
        String result = null;
        for (Path tier : tiers) {
            String candidate = SettingsTreeReader.stringValue(
                SettingsTreeReader.readAccepted(tier, false), key, false);
            if (candidate != null) result = candidate;
        }
        return result;
    }

    /** Test seam for persisted-effort parsing across injected file tiers. */
    static String loadEffortLevel(List<Path> tiers) {
        String value = loadLayeredString("effortLevel", tiers);
        return value != null && isAllowedPersistedEffort(value) ? value : null;
    }

    private static boolean booleanSetting(String key, boolean defaultValue) {
        Boolean value = optionalBooleanSetting(key);
        return value == null ? defaultValue : value;
    }

    private static Boolean optionalBooleanSetting(String key) {
        JsonNode value = effectiveValue(key);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private static int integerSetting(String key, int defaultValue) {
        Integer value = SettingsTreeReader.integerValue(effectiveValue(key));
        return value == null ? defaultValue : value;
    }

    private static String stringSetting(String key) {
        JsonNode value = effectiveValue(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static JsonNode effectiveValue(String key) {
        if (StringUtils.isBlank(key)) return null;
        return SettingsSnapshots.effective(currentCwd()).get(key);
    }

    private static boolean isAllowedPersistedEffort(String value) {
        if (Strings.CS.equals("none", value) || Strings.CS.equals("minimal", value)
                || Strings.CS.equals("low", value) || Strings.CS.equals("medium", value)
                || Strings.CS.equals("high", value) || Strings.CS.equals("xhigh", value)) return true;
        return Strings.CS.equals("max", value)
            && Strings.CS.equals("ant", System.getenv("USER_TYPE"));
    }

    private static String currentCwd() {
        return SettingsPaths.sessionProjectRoot(System.getProperty("user.dir")).toString();
    }
}
