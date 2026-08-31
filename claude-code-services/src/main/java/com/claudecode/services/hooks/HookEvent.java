package com.claudecode.services.hooks;

import org.apache.commons.lang3.StringUtils;
import java.util.Locale;

/**
 * Hook event types covering the full lifecycle: tool execution, session, permissions, compact,
 * tasks, prompt expansion, and display filtering.
 */
public enum HookEvent {
    PRE_TOOL_USE,
    POST_TOOL_USE,
    POST_TOOL_USE_FAILURE,
    POST_TOOL_BATCH,
    NOTIFICATION,
    USER_PROMPT_SUBMIT,
    USER_PROMPT_EXPANSION,
    SESSION_START,
    SESSION_END,
    STOP,
    STOP_FAILURE,
    SUBAGENT_START,
    SUBAGENT_STOP,
    PRE_COMPACT,
    POST_COMPACT,
    PERMISSION_REQUEST,
    PERMISSION_DENIED,
    SETUP,
    TEAMMATE_IDLE,
    TASK_CREATED,
    TASK_COMPLETED,
    ELICITATION,
    ELICITATION_RESULT,
    CONFIG_CHANGE,
    WORKTREE_CREATE,
    WORKTREE_REMOVE,
    INSTRUCTIONS_LOADED,
    CWD_CHANGED,
    FILE_CHANGED,
    MESSAGE_DISPLAY;

    /**
     * Returns the lowercase snake_case name for config matching (e.g., "pre_tool_use").
     */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }




    public String displayName() {
        String[] parts = name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }










    public static HookEvent fromConfigKey(String key) {
        if (StringUtils.isBlank(key)) throw new IllegalArgumentException("blank key");
        // Try SNAKE_CASE / uppercase directly first
        try {
            return valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            // Fall through — try PascalCase → SNAKE_CASE conversion
        }
        // "PreToolUse" → "_Pre_Tool_Use" → "Pre_Tool_Use" → "PRE_TOOL_USE"
        String snake = key.replaceAll("([A-Z])", "_$1").replaceAll("^_", "").toUpperCase(Locale.ROOT);
        return valueOf(snake);
    }
}
