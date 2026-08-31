package com.claudecode.commands.impl.config;

import com.claudecode.commands.AnnotatedCommand;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.ConfigLiveSetters;
import com.claudecode.commands.metadata.SlashCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.commons.lang3.Strings;

/**
 * /config — shows or modifies application-level settings.
 */
@SlashCommand(
    name = "config",
    description = "Open config panel",
    aliases = "settings"
)
public class ConfigCommand implements AnnotatedCommand {

    /** Type of a setting's value, for {@code /config list} formatting and {@code set} parsing. */
    private enum Type { BOOLEAN, STRING, ENUM, INTEGER }

    /** Which file backs a setting. */
    private enum Store { GLOBAL_JSON, SETTINGS_JSON }

    /**
     * One row of the settings table. {@code enumValues} is non-null only for
     * {@code Type.ENUM}.
     */
    private record Setting(
        String key, Type type, Store store, Object defaultValue,
        List<String> enumValues, String description
    ) {}

    private static final List<Setting> SETTINGS = List.of(

        new Setting("autoCompactEnabled", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Automatically compact the conversation when approaching the context limit"),

        // rather than disabled while CLAUDE_CODE_DISABLE_REFUSAL_FALLBACK is set.
        // The gate lives in the panel that offers the row; the setting itself is
        // always addressable by key, which is what `/config set` needs.
        new Setting("switchModelsOnFlag", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Switch models when a message is flagged"),
        new Setting("spinnerTipsEnabled", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Show rotating tips under the spinner while waiting"),
        new Setting("prefersReducedMotion", Type.BOOLEAN, Store.SETTINGS_JSON, false, null,
            "Reduce or disable animations for accessibility"),
        new Setting("thinkingEnabled", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Always-on extended thinking mode"),
        new Setting("awaySummaryEnabled", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Show a session recap after returning from an extended idle period"),
        new Setting("fileCheckpointingEnabled", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Rewind code via checkpoints when using /rewind (takes effect on next session — read once at engine startup, see FileHistorySettings)"),
        new Setting("enableWorkflows", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Enable dynamic workflow orchestration"),
        new Setting("workflowKeywordTriggerEnabled", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Let the ultracode keyword opt a turn into workflow orchestration"),
        new Setting("verbose", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Verbose output — include full API response metadata"),
        new Setting("terminalProgressBarEnabled", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Report turn progress via terminal OSC 9;4 progress bar"),
        new Setting("showTurnDuration", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Show the turn duration message (e.g. \"Cooked for 1m 6s\")"),
        new Setting("defaultPermissionMode", Type.ENUM, Store.SETTINGS_JSON, "default",

            // bypassPermissions because it requires a separate acceptance flow.
            List.of("default", "plan", "acceptEdits", "auto", "dontAsk"),
            "Default permission mode for new sessions"),
        new Setting("worktreeBaseRef", Type.ENUM, Store.SETTINGS_JSON, "fresh",
            List.of("fresh", "head"), "Base ref used for new worktrees"),
        new Setting("useAutoModeDuringPlan", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Use auto-mode semantics while plan mode is active"),
        new Setting("respectGitignore", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Respect .gitignore in the @-file picker"),
        new Setting("copyFullResponse", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Skip the /copy response picker"),
        new Setting("defaultToAgentsView", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Open the agents view by default"),
        new Setting("leftArrowOpensAgents", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Let left arrow open the agents view from an empty prompt"),
        new Setting("autoUpdatesChannel", Type.ENUM, Store.SETTINGS_JSON, "disabled",
            List.of("disabled", "latest", "stable"), "Release channel for auto-updates"),
        new Setting("theme", Type.ENUM, Store.GLOBAL_JSON, "dark",
            List.of("auto", "dark", "light", "light-daltonized", "dark-daltonized", "light-ansi", "dark-ansi"),
            "UI color theme"),
        new Setting("preferredNotifChannel", Type.ENUM, Store.GLOBAL_JSON, "auto",
            List.of("auto", "iterm2", "terminal_bell", "iterm2_with_bell", "kitty",
                "ghostty", "notifications_disabled"),
            "Preferred local notification channel"),
        // output styles are discovered dynamically by getAllOutputStyles.
        new Setting("outputStyle", Type.STRING, Store.SETTINGS_JSON, "default", null,
            "Preferred output style"),
        new Setting("language", Type.STRING, Store.SETTINGS_JSON, "", null,
            "Preferred response and voice language"),
        new Setting("editorMode", Type.ENUM, Store.GLOBAL_JSON, "normal",
            List.of("normal", "vim"),
            "Editor mode"),
        new Setting("externalEditorContext", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Include the last response when opening the external editor"),
        new Setting("prStatusFooterEnabled", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Show pull-request status in the footer"),
        new Setting("autoConnectIde", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Automatically connect to an IDE from an external terminal"),
        new Setting("claudeInChromeDefaultEnabled", Type.BOOLEAN, Store.GLOBAL_JSON, false, null,
            "Enable Claude in Chrome by default")
    );

    /** Java extensions remain addressable through the command but are not shown in 2.1.197 UI. */
    private static final List<Setting> EXTENSION_SETTINGS = List.of(
        new Setting("claudeHudEnabled", Type.BOOLEAN, Store.SETTINGS_JSON, true, null,
            "Show the native claude-hud status display below the prompt"),
        new Setting("subagentMaxDepth", Type.INTEGER, Store.SETTINGS_JSON, 2, null,
            "Maximum ordinary sub-agent nesting depth (1-5)"),
        new Setting("copyOnSelect", Type.BOOLEAN, Store.GLOBAL_JSON, true, null,
            "Auto-copy to clipboard on mouse-up selection")
    );

    private static final Map<String, Setting> BY_KEY = new LinkedHashMap<>();
    static {
        for (Setting s : SETTINGS) BY_KEY.put(s.key().toLowerCase(Locale.ROOT), s);
        for (Setting s : EXTENSION_SETTINGS) BY_KEY.put(s.key().toLowerCase(Locale.ROOT), s);
    }

    private static String storageKey(Setting s) {
        return s.key();
    }

    public ConfigCommand() {}

    /**
     * Setting keys in display order — exposed so the UI Settings panel
     * ({@code ConfigPanel}) can assert its view model stays aligned with this
     * command's canonical list (guard test), without leaking the private
     * {@code SETTINGS} rows or their persistence metadata.
     */
    public static List<String> settingKeys() {
        return SETTINGS.stream().map(Setting::key).toList();
    }

    /** Effective value used by the interactive ThemePicker syntax toggle. */
    public static boolean syntaxHighlightingDisabled(CommandContext context) {
        return context.application().settings().configuration()
            .syntaxHighlightingDisabled();
    }

    /** User-tier persistence seam for ThemePicker's runtime syntax toggle. */
    public static void setSyntaxHighlightingDisabled(
            CommandContext context, boolean disabled) {
        context.application().settings().configuration()
            .saveSyntaxHighlightingDisabled(disabled);
    }

    public static Map<String, String> currentValues(
            Supplier<ObjectNode> globalSnapshotSupplier,
            Supplier<ObjectNode> settingsSnapshotSupplier) {
        return currentValues(globalSnapshotSupplier, settingsSnapshotSupplier,
            settingsSnapshotSupplier);
    }

    public static Map<String, String> currentValues(
            Supplier<ObjectNode> globalSnapshotSupplier,
            Supplier<ObjectNode> settingsSnapshotSupplier,
            Supplier<ObjectNode> userSettingsSnapshotSupplier) {
        ObjectNode globalSnapshot = globalSnapshotSupplier.get();
        ObjectNode settingsSnapshot = settingsSnapshotSupplier.get();
        Map<String, String> values = new LinkedHashMap<>();
        for (Setting s : SETTINGS) {
            values.put(s.key(), currentValueDisplay(s, globalSnapshot, settingsSnapshot));
        }
        return values;
    }

    @Override
    public CommandResult execute(CommandContext context, String args) {

        if (context.presentation().configDialogLauncher() != null) {
            context.presentation().configDialogLauncher().run();
            return CommandResult.skip();
        }
        String action = args != null ? args.trim() : "";
        String lower = action.toLowerCase(Locale.ROOT);

        if (action.isEmpty() || Strings.CS.equals(lower, "show")) {
            return showConfig(context);
        }
        if (Strings.CS.equals(lower, "list")) {
            return listSettings();
        }
        if (Strings.CS.startsWith(lower, "get ")) {
            return getSetting(context, action.substring(4).trim());
        }
        if (Strings.CS.startsWith(lower, "set ")) {
            String[] parts = action.substring(4).trim().split("\\s+", 2);
            if (parts.length < 2) {
                return CommandResult.of("Usage: /config set <key> <value>");
            }
            return setSetting(context, parts[0], parts[1]);
        }
        return showHelp();
    }

    private CommandResult showConfig(CommandContext context) {
        StringBuilder sb = new StringBuilder("Configuration\n=============\n\n");
        Map<String, String> values = context.application().settings()
            .configuration().values(context.session().workingDirectory());
        for (Setting s : SETTINGS) {
            sb.append("  ").append(s.key()).append(" = ").append(values.get(s.key())).append('\n');
        }
        sb.append("\nUse /config list for descriptions.\n");
        sb.append("Use /config get <key> to view a specific value.\n");
        sb.append("Use /config set <key> <value> to modify a value.\n");
        return CommandResult.of(sb.toString());
    }

    private CommandResult listSettings() {
        StringBuilder sb = new StringBuilder("Available Configuration Keys\n=============================\n\n");
        for (Setting s : SETTINGS) {
            sb.append("  ").append(s.key());
            switch (s.type()) {
                case ENUM -> sb.append(" [").append(String.join("|", s.enumValues())).append(']');
                case BOOLEAN -> sb.append(" [boolean]");
                case STRING -> sb.append(" [string]");
                case INTEGER -> sb.append(" [1-5]");
            }
            sb.append('\n').append("    ").append(s.description()).append('\n');
        }
        return CommandResult.of(sb.toString());
    }

    private CommandResult getSetting(CommandContext context, String key) {
        Setting s = BY_KEY.get(key.toLowerCase(Locale.ROOT));
        if (s == null) {
            return CommandResult.of("Unknown config key: " + key + ". Use /config list for available keys.");
        }
        String value = context.application().settings().configuration()
            .values(context.session().workingDirectory()).get(s.key());
        return CommandResult.of(s.key() + " = " + value);
    }

    private CommandResult setSetting(CommandContext context, String key, String rawValue) {
        Setting s = BY_KEY.get(key.toLowerCase(Locale.ROOT));
        if (s == null) {
            return CommandResult.of("Unknown config key: " + key + ". Use /config list for available keys.");
        }
        return switch (s.type()) {
            case BOOLEAN -> setBoolean(context, s, rawValue);
            case ENUM    -> setEnum(context, s, rawValue);
            case STRING  -> setString(context, s, rawValue);
            case INTEGER -> setInteger(context, s, rawValue);
        };
    }

    /**
     * Host-only settings-panel commit seam. This bypasses the slash-command
     * entry point so ConfigPanel can persist a selected row without making
     * user-typed {@code /config set ...} an invented public subcommand.
     */
    public CommandResult applySetting(CommandContext context, String key, String rawValue) {
        return setSetting(context, key, rawValue);
    }

    private CommandResult setBoolean(CommandContext context, Setting s, String rawValue) {
        Boolean value = parseBoolean(rawValue);
        if (value == null) {
            return CommandResult.of("Invalid value for " + s.key() + ": expected true/false, got \"" + rawValue + "\"");
        }
        save(context, s.key(), String.valueOf(value));
        applyLiveBoolean(context, s, value);
        return CommandResult.of(s.key() + " = " + value);
    }

    private CommandResult setEnum(CommandContext context, Setting s, String rawValue) {
        // Whitelist validation up front for every enum — including defaultPermissionMode.

        // by rejecting anything outside enumValues rather than silently coercing to default.
        if (!s.enumValues().contains(rawValue)) {
            return CommandResult.of("Invalid value for " + s.key() + ": expected one of "
                + String.join(", ", s.enumValues()) + ", got \"" + rawValue + "\"");
        }
        save(context, s.key(), rawValue);
        if (Strings.CS.equals(s.key(), "theme") && context.session().configLiveSetters() != null
                && context.session().configLiveSetters().themeSetter() != null) {
            context.session().configLiveSetters().themeSetter().accept(rawValue);
        }
        return CommandResult.of(s.key() + " = " + rawValue);
    }

    private CommandResult setString(CommandContext context, Setting s, String rawValue) {
        save(context, s.key(), rawValue);
        return CommandResult.of(s.key() + " = " + rawValue);
    }

    private CommandResult setInteger(CommandContext context, Setting s, String rawValue) {
        final int value;
        try {
            value = Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException _) {
            return CommandResult.of("Invalid value for " + s.key()
                + ": expected an integer from 1 to 5, got \"" + rawValue + "\"");
        }
        if (value < 1 || value > 5) {
            return CommandResult.of("Invalid value for " + s.key()
                + ": expected an integer from 1 to 5, got \"" + rawValue + "\"");
        }
        save(context, s.key(), String.valueOf(value));
        return CommandResult.of(s.key() + " = " + value);
    }

    private static void save(CommandContext context, String key, String value) {
        context.application().settings().configuration()
            .save(context.session().workingDirectory(), key, value);
    }

    private void applyLiveBoolean(CommandContext context, Setting s, boolean value) {
        ConfigLiveSetters live = context.session().configLiveSetters();
        switch (s.key()) {
            case "verbose" -> {
                if (live != null && live.verboseSetter() != null) live.verboseSetter().accept(value);
            }
            case "autoCompactEnabled" -> {
                if (live != null && live.autoCompactSetter() != null) live.autoCompactSetter().accept(value);
            }
            case "thinkingEnabled" -> {
                if (live != null && live.thinkingEnabledSetter() != null) live.thinkingEnabledSetter().accept(value);
            }
            case "prefersReducedMotion" -> {
                if (live != null && live.reducedMotionSetter() != null) live.reducedMotionSetter().accept(value);
            }
            case "claudeHudEnabled" -> {
                if (live != null && live.claudeHudSetter() != null) live.claudeHudSetter().accept(value);
            }
            default -> { /* no live-apply wiring for this key */ }
        }
    }

    private static String currentValueDisplay(
            Setting s, ObjectNode globalSnapshot, ObjectNode settingsSnapshot) {
        return switch (s.store()) {
            case GLOBAL_JSON -> switch (s.type()) {
                case BOOLEAN -> String.valueOf(booleanValue(
                    globalSnapshot.get(storageKey(s)), (boolean) s.defaultValue()));
                case ENUM, STRING -> stringValue(
                    globalSnapshot.get(storageKey(s)), (String) s.defaultValue());
                case INTEGER -> String.valueOf(integerValue(
                    globalSnapshot.get(storageKey(s)), (int) s.defaultValue()));
            };
            case SETTINGS_JSON -> currentSettingsJsonValue(s, globalSnapshot, settingsSnapshot);
        };
    }

    private static String currentSettingsJsonValue(
            Setting s, ObjectNode globalSnapshot, ObjectNode settingsSnapshot) {
        return switch (s.key()) {
            case "thinkingEnabled" -> String.valueOf(booleanValue(
                settingsSnapshot.get("alwaysThinkingEnabled"), (boolean) s.defaultValue()));
            case "claudeHudEnabled" -> {
                JsonNode configured = settingsSnapshot.get(s.key());
                yield String.valueOf(configured != null && configured.isBoolean()
                    ? configured.asBoolean()
                    : booleanValue(globalSnapshot.get(s.key()), (boolean) s.defaultValue()));
            }
            case "defaultPermissionMode" -> stringValue(
                nested(settingsSnapshot, "permissions", "defaultMode"),
                (String) s.defaultValue());
            case "worktreeBaseRef" -> stringValue(
                nested(settingsSnapshot, "worktree", "baseRef"),
                (String) s.defaultValue());
            case "subagentMaxDepth" -> String.valueOf(validSubagentMaxDepth(
                settingsSnapshot.get(s.key()), (int) s.defaultValue()));
            default -> switch (s.type()) {
                case BOOLEAN -> String.valueOf(booleanValue(
                    settingsSnapshot.get(s.key()), (boolean) s.defaultValue()));
                case ENUM, STRING -> stringValue(
                    settingsSnapshot.get(s.key()), (String) s.defaultValue());
                case INTEGER -> String.valueOf(integerValue(
                    settingsSnapshot.get(s.key()), (int) s.defaultValue()));
            };
        };
    }

    private static int integerValue(JsonNode node, int defaultValue) {
        return node != null && node.isIntegralNumber() ? node.asInt() : defaultValue;
    }

    private static int validSubagentMaxDepth(JsonNode node, int defaultValue) {
        int value = integerValue(node, defaultValue);
        return value >= 1 && value <= 5 ? value : defaultValue;
    }

    private static boolean booleanValue(JsonNode node, boolean defaultValue) {
        return node != null && node.isBoolean() ? node.asBoolean() : defaultValue;
    }

    private static String stringValue(JsonNode node, String defaultValue) {
        return node != null && node.isTextual() ? node.asText() : defaultValue;
    }

    private static JsonNode nested(ObjectNode root, String parent, String key) {
        JsonNode node = root == null ? null : root.get(parent);
        return node != null && node.isObject() ? node.get(key) : null;
    }

    private static Boolean parseBoolean(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(v, "true") || Strings.CS.equals(v, "on") || Strings.CS.equals(v, "1")) return Boolean.TRUE;
        if (Strings.CS.equals(v, "false") || Strings.CS.equals(v, "off") || Strings.CS.equals(v, "0")) return Boolean.FALSE;
        return null;
    }

    private CommandResult showHelp() {
        return CommandResult.of("""
            Config Command
            ==============

            Commands:
              /config            - Show current configuration
              /config list       - List all available config keys with descriptions
              /config get <key>  - Get a specific config value
              /config set <key> <value> - Set a config value
            """);
    }

}
