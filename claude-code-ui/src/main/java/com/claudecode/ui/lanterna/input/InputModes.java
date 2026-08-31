package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
/**
 * Pure, stateless helpers for the bash input-mode prefix: detecting the mode from a raw input's
 * leading character, stripping that prefix, and prepending it on submit.
 */
public final class InputModes {

    private InputModes() {}

    /**
     * Detect the mode implied by {@code text}'s leading character: {@code '!'} → BASH, otherwise
     * NORMAL.
     */
    static InputPanel.Mode fromPrefix(String text) {
        return StringUtils.isNotEmpty(text) && text.charAt(0) == '!'
            ? InputPanel.Mode.BASH
            : InputPanel.Mode.NORMAL;
    }

    /**
     * As {@link #fromPrefix} but returns {@code null} for NORMAL, matching InputPanel's
     * {@code modeOverride} convention (null = auto-detect / no override).
     */
    static InputPanel.Mode overrideFromPrefix(String text) {
        InputPanel.Mode m = fromPrefix(text);
        return m == InputPanel.Mode.NORMAL ? null : m;
    }

    /**
     * Strip the leading mode-prefix character if present.
     */
    static String stripPrefix(String text) {
        if (text == null) return null;
        return fromPrefix(text) == InputPanel.Mode.NORMAL ? text : text.substring(1);
    }

    /**
     * Prepend the mode-prefix character for {@code mode} unless {@code text} already carries it (or the
     * mode is NORMAL).
     */
    static String prependPrefix(String text, InputPanel.Mode mode) {
        if (mode == InputPanel.Mode.BASH
                && (StringUtils.isEmpty(text) || text.charAt(0) != '!')) return "!" + text;
        return text;
    }
}
