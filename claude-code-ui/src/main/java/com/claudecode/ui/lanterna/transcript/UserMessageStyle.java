package com.claudecode.ui.lanterna.transcript;

/**
 * Layout and truncation constants for the user-prompt message bubble.
 */
public final class UserMessageStyle {

    private UserMessageStyle() {}

    /**
     * Hard cap on displayed prompt text. Piping large files via stdin
     * (e.g. {@code cat 11k-line-file | claude}) creates a single user message
     * whose render-pass costs 500 ms+ per keystroke if uncapped. Above this
     * size, the message is replaced with a head+tail summary so the user can
     * still see what they pasted at both ends.
     */
    public static final int MAX_DISPLAY_CHARS    = 10_000;
    /** Characters preserved from the start of the original text. */
    public static final int TRUNCATE_HEAD_CHARS  = 2_500;
    /** Characters preserved from the end of the original text. */
    public static final int TRUNCATE_TAIL_CHARS  = 2_500;


    public static final int PADDING_RIGHT = 1;

    public static final int PADDING_LEFT = 1;


    public static final int QUEUED_INDENT = 2;


    public static String truncateForDisplay(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_DISPLAY_CHARS) return text;

        String head = text.substring(0, TRUNCATE_HEAD_CHARS);
        String tail = text.substring(text.length() - TRUNCATE_TAIL_CHARS);
        int headNewlines = countChar(text, '\n', 0, TRUNCATE_HEAD_CHARS);
        int tailNewlines = countChar(tail, '\n', 0, tail.length());
        int totalNewlines = countChar(text, '\n', 0, text.length());
        int hiddenLines = totalNewlines - headNewlines - tailNewlines;
        return head + "\n… +" + hiddenLines + " lines …\n" + tail;
    }

    private static int countChar(String s, char target, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == target) count++;
        }
        return count;
    }
}
