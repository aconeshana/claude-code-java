package com.claudecode.ui.render;

import com.claudecode.ui.lanterna.transcript.MessagePanel;

/**
 * Polymorphic indicator renderer for tool-use rows.
 */
public sealed interface ToolUseIndicatorRenderer
        permits DotIndicatorRenderer, SpinnerIndicatorRenderer {

    /**
     * Emits the tool-use indicator into {@code panel} for the given {@code ctx}.
     *
     * @param panel the target {@link MessagePanel}; must not be {@code null}
     * @param ctx   the current rendering context; must not be {@code null}
     */
    void render(MessagePanel panel, RenderingContext ctx);

    /**
     * Selects the correct implementation for the supplied context.
     *
     * <p>Queued preview → {@link DotIndicatorRenderer#INSTANCE} (static dim dot).<br>
     * Normal in-progress → {@link SpinnerIndicatorRenderer#INSTANCE} (animated spinner).
     *
     * @param ctx must not be {@code null}
     * @return the appropriate singleton renderer
     */
    static ToolUseIndicatorRenderer pick(RenderingContext ctx) {
        return ctx.isInQueuedPreview()
                ? DotIndicatorRenderer.INSTANCE
                : SpinnerIndicatorRenderer.INSTANCE;
    }
}
