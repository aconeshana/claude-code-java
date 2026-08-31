package com.claudecode.keybindings;

import org.apache.commons.lang3.StringUtils;
/**
 * Runtime shortcut labels used by terminal hints.
 */
public final class KeybindingHints {

    private KeybindingHints() {}

    public static String shortcut(UserKeybindingsStore store, String action,
                                  String context, String fallback) {
        String resolved = store == null ? null
            : store.currentResolver().getBindingDisplayText(action, context);
        return StringUtils.isBlank(resolved) ? fallback : resolved;
    }

    public static String expand(UserKeybindingsStore store) {
        String resolved = store == null ? null
            : store.currentResolver().getBindingDisplayText(
                "app:toggleTranscript", "Global");
        return StringUtils.isBlank(resolved)
            ? DefaultBindings.EXPAND_HINT
            : "(" + resolved + " to expand)";
    }
}
