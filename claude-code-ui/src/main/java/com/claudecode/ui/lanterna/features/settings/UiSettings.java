package com.claudecode.ui.lanterna.features.settings;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.config.SettingsPathResolver;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.RuleSource;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;

/**
 * Accessors and application-owned write ports for the handful of (and policy-managed fields the
 * REPL consumes.
 */
public final class UiSettings {

    /** Sandbox dependency diagnostics displayed by the four-tab /sandbox panel. */
    public record SandboxDependencyStatus(List<String> errors, List<String> warnings) {
        public static final SandboxDependencyStatus READY =
            new SandboxDependencyStatus(List.of(), List.of());

        public SandboxDependencyStatus {
            errors = List.copyOf(errors == null ? List.of() : errors);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    /**
     * Application-owned settings operations consumed by the presentation layer.
     * The CLI installs the production implementation before constructing the REPL;
     * tests may install a deterministic in-memory implementation.
     */
    public interface Backend {
        boolean globalBoolean(String key, boolean defaultValue);

        String globalString(String key, String defaultValue);

        int globalInt(String key, int defaultValue);

/** Returns a structured value from, or {@code null}. */
        default JsonNode globalNode(String key) { return null; }

        /** Returns a value from the effective merged settings snapshot, or {@code null}. */
        default JsonNode effectiveSetting(String key) { return null; }

        /** Returns a string from the user settings source only, or {@code null}. */
        default String userSettingString(String key) { return null; }

        void setGlobal(String key, Object value);

/** Writes a top-level value to. */
        default void setUserSetting(String key, Object value) { }

        /** Returns the global, time-decayed skill usage ranking. */
        default Map<String, Double> skillUsageScores() { return Map.of(); }

        /** Trusted policy/user/flag suppression for the auto-mode entry warning. */
        default boolean skipAutoPermissionPrompt() { return false; }

        /** Apply the full settings.env overlay after interactive trust gates complete. */
        default void applyTrustedEnvironment(String cwd) { }

        /** Whether the dangerous bypass startup acknowledgement was already persisted. */
        default boolean skipDangerousModePermissionPrompt() { return false; }

        /** Persist the dangerous bypass startup acknowledgement. */
        default void persistDangerousModePermissionPrompt() { }

        boolean spinnerTipsEnabled();

        boolean prefersReducedMotion();

        Boolean policyBoolean(String key);

        SandboxConfig sandboxConfig();

        default SandboxDependencyStatus sandboxDependencyStatus() {
            return SandboxDependencyStatus.READY;
        }

        default boolean sandboxSettingsLockedByPolicy() {
            return false;
        }

        default void setSandboxSettings(Boolean enabled,
                                        Boolean autoAllowBashIfSandboxed,
                                        Boolean allowUnsandboxedCommands) { }

        void addPermissionRule(String cwd, PermissionBehavior behavior,
                               String ruleString, RuleSource tier);

        void removePermissionRule(String cwd, PermissionBehavior behavior,
                                  String ruleString, RuleSource tier);

        /** Persist one update when its destination is an editable settings tier. */
        default void persistPermissionUpdate(String cwd, PermissionUpdate update) { }

    }

    private static final Backend FALLBACK = new Backend() {
        @Override public boolean globalBoolean(String key, boolean defaultValue) {
            JsonNode node = readJsonNode(ClaudePaths.GLOBAL_JSON, key);
            return node != null && node.isBoolean() ? node.booleanValue() : defaultValue;
        }
        @Override public String globalString(String key, String defaultValue) {
            JsonNode node = readJsonNode(ClaudePaths.GLOBAL_JSON, key);
            return node != null && node.isTextual() ? node.asText() : defaultValue;
        }
        @Override public int globalInt(String key, int defaultValue) {
            JsonNode node = readJsonNode(ClaudePaths.GLOBAL_JSON, key);
            return node != null && node.isInt() ? node.intValue() : defaultValue;
        }
        @Override public JsonNode globalNode(String key) {
            JsonNode node = readJsonNode(ClaudePaths.GLOBAL_JSON, key);
            return node == null ? null : node.deepCopy();
        }
        @Override public JsonNode effectiveSetting(String key) {
            return readJsonNode(SettingsPathResolver.userSettingsPath(), key);
        }
        @Override public String userSettingString(String key) {
            JsonNode node = readJsonNode(SettingsPathResolver.userSettingsPath(), key);
            return node != null && node.isTextual() ? node.asText() : null;
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
    };

    private static volatile Backend backend = FALLBACK;
    /** Process-ordered settings writes; one worker prevents RMW lost updates. */
    private static final ExecutorService USER_SETTINGS_WRITER =
        Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("ui-settings-writer-", 0).factory());

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform()
            .name("ui-settings-flush")
            .unstarted(() -> {
                USER_SETTINGS_WRITER.shutdown();
                try {
                    if (!USER_SETTINGS_WRITER.awaitTermination(3, TimeUnit.SECONDS)) {
                        USER_SETTINGS_WRITER.shutdownNow();
                    }
                } catch (InterruptedException _) {
                    USER_SETTINGS_WRITER.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }));
    }

    private UiSettings() {}

    public static void configure(Backend configured) {
        backend = Objects.requireNonNullElse(configured, FALLBACK);
    }

    


    public static boolean readCopyOnSelect() {
        return backend.globalBoolean("copyOnSelect", true);
    }


    public static boolean isVimModeEnabled() {
        return Strings.CS.equals("vim", backend.globalString("editorMode", "normal"));
    }

    /**
     * Compute the effort suffix shown in the spinner.
     */
    public static String readEffortSuffix(String model) {
        String envOverride = SubprocessEnvironment.get(
            "CLAUDE_CODE_EFFORT_LEVEL");
        String effectiveLevel;
        if (StringUtils.isNotBlank(envOverride)) {
            String lower = envOverride.toLowerCase(Locale.ROOT);
            // "unset" / "auto" → no override, fall through to settings
            if (Strings.CS.equals("unset", lower) || Strings.CS.equals("auto", lower)) {
                return "";
            }
            effectiveLevel = lower;
        } else {
// Read effortLevel from ~/on
            effectiveLevel = readStringFromSettings("effortLevel");
            if (effectiveLevel == null) return "";
        }
        // Clamp max only when the selected model's capability table does not support it.
        if (Strings.CS.equals("max", effectiveLevel)
                && !EffortHelpers.modelSupportsMaxEffort(model)) {
            effectiveLevel = "high";
        }
        return " with " + effectiveLevel + " effort";
    }


    public static String readStringFromSettings(String key) {
        JsonNode node = backend.effectiveSetting(key);
        if (node != null) return node.isTextual() ? node.asText() : null;
        return readUserStringFromSettings(key);
    }

    /** Read a string key from the user settings source only. */
    public static String readUserStringFromSettings(String key) {
        String configured = backend.userSettingString(key);
        if (configured != null) return configured;
        JsonNode node = readJsonNode(SettingsPathResolver.userSettingsPath(), key);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** Read one value from the effective settings snapshot for UI consumers. */
    public static JsonNode readEffectiveSetting(String key) {
        JsonNode node = backend.effectiveSetting(key);
        return node == null ? null : node.deepCopy();
    }

    /** Effective layered syntax-highlighting toggle used by the markdown renderer. */
    public static boolean readSyntaxHighlightingDisabled() {
        JsonNode node = backend.effectiveSetting("syntaxHighlightingDisabled");
        return node != null && node.isBoolean() && node.booleanValue();
    }

    /**
     * Reads {@code spinnerTipsEnabled} from the layered settings (user → project → local, local wins;
     * written to the local tier).
     */
    public static boolean readSpinnerTipsEnabled() {
        return backend.spinnerTipsEnabled();
    }

/** Reads {@code disableAllHooks} from  top-level field. */
    static boolean readDisableAllHooks() {
        JsonNode node = backend.effectiveSetting("disableAllHooks");
        return node != null && node.isBoolean() && node.booleanValue();
    }

/**
     * Reads {@code disableAllHooks} from the policy-managed settings file.
     */
    static boolean readDisableAllHooksByPolicy() {
        return readPolicyBooleanFlag("disableAllHooks");
    }

    /**
     * Reads {@code allowManagedHooksOnly} from the policy-managed settings file.
     */
    static boolean readAllowManagedHooksOnlyByPolicy() {
        return readPolicyBooleanFlag("allowManagedHooksOnly");
    }

    /**
     * Shared reader for top-level boolean flags in the policy-managed settings
     * file. Absent file / missing
     * key / malformed JSON all degrade to {@code false} — a broken policy file
     * must not accidentally lock users out.
     */
    private static boolean readPolicyBooleanFlag(String key) {
        Boolean v = backend.policyBoolean(key);
        return v != null && v;
    }

    public static boolean readGlobalBoolean(String key, boolean defaultValue) {
        return backend.globalBoolean(key, defaultValue);
    }

    /** Built-in HUD gate. */
    @Explanation("Enables the built-in status-line HUD by default")
    public static boolean isClaudeHudEnabled() {
        JsonNode configured = backend.effectiveSetting("claudeHudEnabled");
        if (configured != null && configured.isBoolean()) return configured.asBoolean();
        // Read-only compatibility for builds that persisted the first version

        return backend.globalBoolean("claudeHudEnabled", true);
    }

    public static int readGlobalInt(String key, int defaultValue) {
        return backend.globalInt(key, defaultValue);
    }

    public static JsonNode readGlobalNode(String key) {
        JsonNode node = backend.globalNode(key);
        return node == null ? null : node.deepCopy();
    }

    public static void writeGlobal(String key, Object value) {
        backend.setGlobal(key, value);
    }

    /** Serializes a global-config write away from Lanterna's GUI thread. */
    public static CompletableFuture<Void> writeGlobalAsync(String key, Object value) {
        Backend captured = backend;
        return CompletableFuture.runAsync(() -> captured.setGlobal(key, value),
            USER_SETTINGS_WRITER);
    }

    /**
     * Reads and conditionally persists a boolean on the serialized settings
     * worker. Intended for key handlers whose bookkeeping must never touch the
     * filesystem on Lanterna's event thread.
     */
    public static CompletableFuture<Void> ensureGlobalBooleanAsync(
            String key, boolean value) {
        Backend captured = backend;
        return CompletableFuture.runAsync(() -> {
            if (captured.globalBoolean(key, !value) != value) {
                captured.setGlobal(key, value);
            }
        }, USER_SETTINGS_WRITER);
    }

/** Writes a preference to the user settings tier, not. */
    public static void writeUserSetting(String key, Object value) {
        backend.setUserSetting(key, value);
    }

    /**
     * Serializes a user-setting write away from Lanterna's GUI thread.
     * Capturing the backend at submission time keeps tests and startup wiring
     * deterministic even if a later {@link #configure(Backend)} call swaps it.
     */
    public static CompletableFuture<Void> writeUserSettingAsync(String key, Object value) {
        Backend target = backend;
        return CompletableFuture.runAsync(
            () -> target.setUserSetting(key, value), USER_SETTINGS_WRITER);
    }

    public static Map<String, Double> readSkillUsageScores() {
        return backend.skillUsageScores();
    }

    static boolean readSkipAutoPermissionPrompt() {
        return backend.skipAutoPermissionPrompt();
    }

    public static void applyTrustedEnvironment(String cwd) {
        backend.applyTrustedEnvironment(cwd);
    }

    public static boolean readSkipDangerousModePermissionPrompt() {
        return backend.skipDangerousModePermissionPrompt();
    }

    public static boolean readEffectiveBoolean(String key, boolean defaultValue) {
        JsonNode value = backend.effectiveSetting(key);
        return value != null && value.isBoolean() ? value.asBoolean() : defaultValue;
    }

    public static void persistDangerousModePermissionPrompt() {
        backend.persistDangerousModePermissionPrompt();
    }

    public static boolean readPrefersReducedMotion() {
        return backend.prefersReducedMotion();
    }

    public static SandboxConfig readSandboxConfig() {
        return backend.sandboxConfig();
    }

    public static SandboxDependencyStatus readSandboxDependencyStatus() {
        return backend.sandboxDependencyStatus();
    }

    public static boolean areSandboxSettingsLockedByPolicy() {
        return backend.sandboxSettingsLockedByPolicy();
    }

    public static void writeSandboxSettings(Boolean enabled,
                                            Boolean autoAllowBashIfSandboxed,
                                            Boolean allowUnsandboxedCommands) {
        backend.setSandboxSettings(enabled, autoAllowBashIfSandboxed,
            allowUnsandboxedCommands);
    }

    static void addPermissionRule(String cwd, PermissionBehavior behavior,
                                  String ruleString, RuleSource tier) {
        backend.addPermissionRule(cwd, behavior, ruleString, tier);
    }

    static CompletableFuture<Void> addPermissionRuleAsync(String cwd,
            PermissionBehavior behavior, String ruleString, RuleSource tier) {
        Backend target = backend;
        return CompletableFuture.runAsync(
            () -> target.addPermissionRule(cwd, behavior, ruleString, tier),
            USER_SETTINGS_WRITER);
    }

    static void removePermissionRule(String cwd, PermissionBehavior behavior,
                                     String ruleString, RuleSource tier) {
        backend.removePermissionRule(cwd, behavior, ruleString, tier);
    }

    static CompletableFuture<Void> removePermissionRuleAsync(String cwd,
            PermissionBehavior behavior, String ruleString, RuleSource tier) {
        Backend target = backend;
        return CompletableFuture.runAsync(
            () -> target.removePermissionRule(cwd, behavior, ruleString, tier),
            USER_SETTINGS_WRITER);
    }

    public static void persistPermissionUpdates(String cwd, List<PermissionUpdate> updates) {
        if (updates == null) return;
        for (PermissionUpdate update : updates) {
            if (update != null) backend.persistPermissionUpdate(cwd, update);
        }
    }

    private static JsonNode readJsonNode(Path file, String key) {
        if (!Files.isReadable(file)) return null;
        try {
            return JsonUtils.getMapper().readTree(file.toFile()).get(key);
        } catch (Exception _) {
            return null;
        }
    }

}
