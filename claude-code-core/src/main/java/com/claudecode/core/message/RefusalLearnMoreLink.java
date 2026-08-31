package com.claudecode.core.message;

/**
 * Where a refusal body's trailing {@code learn more: <url>} run ends and the link begins.
 */
public final class RefusalLearnMoreLink {

    /** All the user sees of the url once the run has been collapsed. */
    public static final String LINK_TEXT = "learn more";


    private static final String MARKER = LINK_TEXT + ": " + RefusalErrorMessage.LEARN_MORE_URL;

    /**
     * A body cut into the text before the link, the link, and the text after it.
     * {@code linked} is false when there is nothing to collapse, and then
     * {@code head} holds the whole body — a caller that ignores the flag and
     * renders all three parts still shows the right text, only without the link.
     */
    public record Split(String head, boolean linked, String tail) {

        /** The url the link points at, or {@code null} when nothing was linked. */
        public String url() {
            return linked ? RefusalErrorMessage.LEARN_MORE_URL : null;
        }
    }

    private RefusalLearnMoreLink() {
    }

    /**
     * @param content a refusal announcement or dialog body @param hyperlinksSupported.
     */
    public static Split split(String content, boolean hyperlinksSupported) {
        String body = content == null ? "" : content;
        int marker = body.indexOf(MARKER);
        if (marker < 0 || !hyperlinksSupported) {
            return new Split(body, false, "");
        }
        return new Split(body.substring(0, marker), true,
            body.substring(marker + MARKER.length()));
    }
}
