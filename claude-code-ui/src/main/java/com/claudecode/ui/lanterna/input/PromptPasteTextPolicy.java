package com.claudecode.ui.lanterna.input;

/**
 * Pure text normalization and threshold decisions shared by the prompt's paste paths.
 */
final class PromptPasteTextPolicy {

    private static final int PASTE_THRESHOLD = 800;

    private PromptPasteTextPolicy() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        // ESC [ ... command — strip the same simple CSI sequences as InputPanel did.
        String normalized = raw.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replace("\t", "    ");
    }

    static boolean shouldFoldIntoChip(String normalized, int numLines) {
        return normalized.length() > PASTE_THRESHOLD || numLines > 0;
    }

    static boolean looksLikeUnbracketedPaste(String text) {
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lineCount++;
        }
        return text.length() > PASTE_THRESHOLD
            || (lineCount > 6 && text.length() > 200);
    }
}
