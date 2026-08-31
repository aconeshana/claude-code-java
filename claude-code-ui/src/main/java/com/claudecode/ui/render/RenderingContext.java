package com.claudecode.ui.render;

/**
 * Rendering context passed to renderers that need to adapt their output for queued-message preview
 * mode (the "pending" list shown before a response arrives).
 */
public record RenderingContext(boolean isInQueuedPreview, boolean isFirstInQueue, int paddingWidth) {

    /** Normal (non-queued) rendering — no padding adjustment needed. */
    public static final RenderingContext NORMAL = new RenderingContext(false, false, 0);

    /**
     * Creates a context for a queued-preview item.
     */
    public static RenderingContext queuedPreview(boolean isFirst, int padding) {
        return new RenderingContext(true, isFirst, padding);
    }
}
