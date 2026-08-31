package com.claudecode.services.hooks;




public enum HookSource {
    USER_SETTINGS,
    PROJECT_SETTINGS,
    LOCAL_SETTINGS,
    POLICY_SETTINGS,
    PLUGIN_HOOK,
    SESSION_HOOK,
    BUILTIN_HOOK;

/**
     * Full description string shown in detail view.
     */
    public String descriptionDisplay() {
        return switch (this) {
            case USER_SETTINGS    -> "User settings (~/.claude/settings.json)";
            case PROJECT_SETTINGS -> "Project settings (.claude/settings.json)";
            case LOCAL_SETTINGS   -> "Local settings (.claude/settings.local.json)";
            case POLICY_SETTINGS  -> "Policy settings (managed)";
            case PLUGIN_HOOK      -> "Plugin hooks (~/.claude/plugins/on)";
            case SESSION_HOOK     -> "Session hooks (in-memory, temporary)";
            case BUILTIN_HOOK     -> "Built-in hooks (registered internally by Claude Code)";
        };
    }

    /**
     * Title Case header label shown as right-column description in SELECT_HOOK.
     */
    public String headerDisplay(String pluginName) {
        return switch (this) {
            case USER_SETTINGS    -> "User Settings";
            case PROJECT_SETTINGS -> "Project Settings";
            case LOCAL_SETTINGS   -> "Local Settings";
            case POLICY_SETTINGS  -> "Policy Settings";
            case PLUGIN_HOOK      -> "Plugin Hooks";
            case SESSION_HOOK     -> "Session Hooks";
            case BUILTIN_HOOK     -> "Built-in Hooks";
        };
    }

/**
     * Short inline label shown next to matchers.
     */
    public String inlineDisplay() {
        return switch (this) {
            case USER_SETTINGS    -> "User";
            case PROJECT_SETTINGS -> "Project";
            case LOCAL_SETTINGS   -> "Local";
            case POLICY_SETTINGS  -> "Policy";
            case PLUGIN_HOOK      -> "Plugin";
            case SESSION_HOOK     -> "Session";
            case BUILTIN_HOOK     -> "Built-in";
        };
    }
}
