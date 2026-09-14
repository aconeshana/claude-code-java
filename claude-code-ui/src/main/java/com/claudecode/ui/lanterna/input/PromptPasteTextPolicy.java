package com.claudecode.ui.lanterna.input;

/**
 * Pure text normalization and threshold decisions shared by the prompt's paste paths.
 */
final class PromptPasteTextPolicy {

    private static final int PASTE_THRESHOLD = 800;

    /** Terminal height assumed when no screen is attached (headless, unit tests). */
    static final int DEFAULT_TERMINAL_ROWS = 24;

    private PromptPasteTextPolicy() {}

    static String normalize(String raw) {
        if (raw == null) return "";
        // ESC [ ... command — strip the same simple CSI sequences as InputPanel did.
        String normalized = raw.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replace("\t", "    ");
    }

    /**
     * Official 2.1.236 folds a paste into a chip when its length clears
     * {@code cur = 800} or when its newline count clears a terminal-height
     * derived cap {@code Math.max(0, Math.min(rows - 10, 2))}. The cap keeps a
     * two-line paste editable on a tall terminal but folds it on a short one.
     */
    static boolean shouldFoldIntoChip(String normalized, int numLines, int terminalRows) {
        int lineLimit = Math.max(0, Math.min(terminalRows - 10, 2));
        return normalized.length() > PASTE_THRESHOLD || numLines > lineLimit;
    }
}
