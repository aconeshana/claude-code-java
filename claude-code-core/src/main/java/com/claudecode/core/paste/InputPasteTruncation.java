package com.claudecode.core.paste;



/** Mid-text truncation for oversized prompt-input values. */
public final class InputPasteTruncation {

    private InputPasteTruncation() {}

    /** Characters before we truncate. */
    public static final int TRUNCATION_THRESHOLD = 10_000;

    /** Characters to show at start and end (split evenly between the two). */
    public static final int PREVIEW_LENGTH = 1_000;


    public record Truncated(String truncatedText, String placeholderContent) {}

    /**
     * Truncates {@code text} when it exceeds {@link #TRUNCATION_THRESHOLD}, inserting a
     * {@code [...Truncated text #id +M lines...]} reference in place of the middle.
     *
     * @param text        the current input text
     * @param nextPasteId the reference id the caller will register the middle under
     * @return the display text plus the lifted-out middle (empty when no truncation happened)
     */
    public static Truncated maybeTruncateMessageForInput(String text, int nextPasteId) {
        if (text == null || text.length() <= TRUNCATION_THRESHOLD) {
            return new Truncated(text, "");
        }

        int startLength = PREVIEW_LENGTH / 2;
        int endLength   = PREVIEW_LENGTH / 2;

        String startText          = text.substring(0, startLength);
        String endText            = text.substring(text.length() - endLength);
        String placeholderContent = text.substring(startLength, text.length() - endLength);

        int truncatedLines = PastedRefParser.getPastedTextRefNumLines(placeholderContent);
        String placeholderRef = formatTruncatedTextRef(nextPasteId, truncatedLines);

        return new Truncated(startText + placeholderRef + endText, placeholderContent);
    }

    /** {@code [...Truncated text #N +M lines...]}. */
    static String formatTruncatedTextRef(int id, int numLines) {
        return "[...Truncated text #" + id + " +" + numLines + " lines...]";
    }
}
