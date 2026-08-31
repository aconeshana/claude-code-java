package com.claudecode.ui.lanterna.input;

import org.apache.commons.lang3.StringUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

/**
 * Shared terminal text-input keystroke handling for single-line, end-append buffers (no cursor / no
 * mid-string editing).
 */
public final class TextInputs {

    private TextInputs() {}

    /** Applies a text keystroke to {@code buf}; does nothing for non-text keys. */
    public static void applyKey(StringBuilder buf, KeyStroke key, boolean trim) {
        apply(buf, key, trim);
    }

    /** Applies a text keystroke; returns {@code true} if a text key was consumed. */
    public static boolean tryApplyKey(StringBuilder buf, KeyStroke key, boolean trim) {
        return apply(buf, key, trim);
    }

    private static boolean apply(StringBuilder buf, KeyStroke key, boolean trim) {
        KeyType t = key.getKeyType();
        if (t == KeyType.BACKSPACE) {
            if (!buf.isEmpty()) {
                buf.deleteCharAt(buf.length() - 1);
            }
            return true;
        }
        if (t == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
            String pasted = pks.getPastedText();
            if (StringUtils.isNotEmpty(pasted)) {
                String normalized = pasted.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                buf.append(trim ? normalized.trim() : normalized);
            }
            return true;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && key.getCharacter() >= 0x20 && !key.isCtrlDown() && !key.isAltDown()) {
            buf.append(key.getCharacter().charValue());
            return true;
        }
        return false;
    }
}
