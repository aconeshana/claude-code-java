package com.claudecode.ui.render;

/**
 * Immutable style descriptor for a thinking-block label and its token text.
 */
public record ThinkingStyle(String labelStyle, String tokenStyle) {

    /** Style used when the thinking block is shown in normal (non-queued) context. */
    public static final ThinkingStyle NORMAL = new ThinkingStyle("briefLabelYou", "text");

    /** Style used when the thinking block is shown in a queued-input preview. */
    public static final ThinkingStyle DIM    = new ThinkingStyle("subtle",        "subtle");

    /**
     * Selects the appropriate style for {@code ctx}.
     *
     * @param ctx the current rendering context; must not be {@code null}
     * @return {@link #DIM} when {@link RenderingContext#isInQueuedPreview} is
     *         {@code true}, {@link #NORMAL} otherwise
     */
    public static ThinkingStyle forContext(RenderingContext ctx) {
        return ctx.isInQueuedPreview() ? DIM : NORMAL;
    }
}
