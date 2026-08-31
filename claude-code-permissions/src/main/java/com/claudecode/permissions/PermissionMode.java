package com.claudecode.permissions;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.model.PermissionModeKind;

/**
 * Permission mode controlling the overall permission behavior.
 */
public enum PermissionMode {
    /** Default mode — ask user for permission on write operations. */
    DEFAULT(            new Config("Default",            "Default",  "",     ColorKey.TEXT,        "default")),
    /** Plan mode — deny write operations, allow read operations. */
    PLAN(               new Config("Plan Mode",          "Plan",     Figures.PAUSE_ICON, ColorKey.PLAN_MODE,   "plan")),
    /** Accept file edits without asking. */
    ACCEPT_EDITS(       new Config("Accept edits",       "Accept",   "⏵⏵", ColorKey.AUTO_ACCEPT, "acceptEdits")),
    /** Bypass all permission checks — allow everything. */
    BYPASS_PERMISSIONS( new Config("Bypass Permissions", "Bypass",   "⏵⏵", ColorKey.ERROR,       "bypassPermissions")),
    /** Don't ask for any permissions. */
    DONT_ASK(           new Config("Don't Ask",          "DontAsk",  "⏵⏵", ColorKey.ERROR,       "dontAsk")),
    /** Auto mode — use classifier to decide. ant-only; external falls back to default. */
    AUTO(               new Config("Auto mode",          "Auto",     "⏵⏵", ColorKey.WARNING,     "auto"));

    /**
     * Theme color key for the mode label.
     */
    public enum ColorKey { TEXT, PLAN_MODE, PERMISSION, AUTO_ACCEPT, ERROR, WARNING }


    public record Config(
        String title,

        String shortTitle,
        String symbol,
        ColorKey colorKey,
        String external
    ) {}

    private final Config config;

    PermissionMode(Config config) { this.config = config; }

/** Full title (e.g. "Bypass Permissions"). matches {@code permissionModeTitle}. */
    public String title()      { return config.title; }
/** Short title (e.g. "Bypass"). matches {@code permissionModeShortTitle}. */
    public String shortTitle() { return config.shortTitle; }
/** Symbol glyph ({@code Figures.PAUSE_ICON} / "⏵⏵" / ""). matches {@code permissionModeSymbol}. */
    public String symbol()     { return config.symbol; }
/** Theme color key. matches {@code getModeColor}. */
    public ColorKey colorKey() { return config.colorKey; }
/** External name as serialized in SDK output. matches {@code toExternalPermissionMode}. */
    public String external()   { return config.external; }
    /** Full config bundle. */
    public Config config()     { return config; }

/** True if this is the implicit default mode (or null). matches {@code isDefaultMode}. */
    public static boolean isDefault(PermissionMode mode) {
        return mode == null || mode == DEFAULT;
    }

    /**
     * Bridges to core's dependency-free plan-mode identity. Because
     * {@code core} cannot depend on this module, core's internal
     * branches (e.g. {@code ModelNames#runtimeMainLoopModel}'s plan-mode
     * check) consume {@link PermissionModeKind}
     * instead of this richer, UI-flavored enum.
     */
    public PermissionModeKind kind() {
        return switch (this) {
            case DEFAULT             -> PermissionModeKind.DEFAULT;
            case PLAN                -> PermissionModeKind.PLAN;
            case ACCEPT_EDITS        -> PermissionModeKind.ACCEPT_EDITS;
            case BYPASS_PERMISSIONS  -> PermissionModeKind.BYPASS_PERMISSIONS;
            case DONT_ASK            -> PermissionModeKind.DONT_ASK;
            case AUTO                -> PermissionModeKind.AUTO;
        };
    }

    /**
     * Parse a permission-mode string.
     */
    public static PermissionMode fromString(String s) {
        if (s == null) return DEFAULT;
        return switch (s) {
            case "default"           -> DEFAULT;
            case "plan"              -> PLAN;
            case "acceptEdits"       -> ACCEPT_EDITS;
            case "bypassPermissions" -> BYPASS_PERMISSIONS;
            case "dontAsk"           -> DONT_ASK;
            case "auto"              -> AUTO;
            default                  -> DEFAULT;
        };
    }
}
