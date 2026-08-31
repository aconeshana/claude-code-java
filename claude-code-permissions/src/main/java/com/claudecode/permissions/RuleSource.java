package com.claudecode.permissions;

/**
 * Source of a permission rule, indicating where the rule was defined.
 */
public enum RuleSource {
/** Rule from user-level settings (~/on). */
    USER_SETTINGS,
/** Rule from project-level settings (on). */
    PROJECT_SETTINGS,
/** Rule from local settings (on). */
    LOCAL_SETTINGS,
    /** Rule from feature flags. */
    FLAG_SETTINGS,
    /** Rule from organization policy. */
    POLICY_SETTINGS,
    /** Rule from CLI argument. */
    CLI_ARG,
    /** Rule from a slash command. */
    COMMAND,
    /** Rule from the current session (runtime). */
    SESSION,
    /** Rule from a skill's frontmatter (per-invocation, cleaned up after turn). */
    SKILL;

    /**
     * Human-readable, lowercase source name for warning/fix messages.
     */
    public String displayName() {
        return switch (this) {
            case USER_SETTINGS    -> "user settings";
            case PROJECT_SETTINGS -> "shared project settings";
            case LOCAL_SETTINGS   -> "project local settings";
            case FLAG_SETTINGS    -> "command line arguments";
            case POLICY_SETTINGS  -> "enterprise managed settings";
            case CLI_ARG          -> "CLI argument";
            case COMMAND          -> "command configuration";
            case SESSION          -> "current session";
            case SKILL            -> "skill configuration";
        };
    }
}
