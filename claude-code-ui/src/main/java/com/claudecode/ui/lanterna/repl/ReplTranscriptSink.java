package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.TextColor;

/**
 * Transcript write port shared by every REPL feature: dim system lines, colored lines, and the
 * {@code " ❯ /cmd"} slash-command breadcrumb chip. Features write through this port instead of
 * reaching back into the screen; the production implementation is
 * {@link MessagePanelTranscriptSink}.
 */
public interface ReplTranscriptSink {

    /**
     * Posts a dim inline system notification, thread-safely (marshals to the GUI thread).
     * Consolidates {@code LanternaReplScreen.postSystemMessage}.
     */
    void system(String text);

    /**
     * Echoes a slash-command breadcrumb chip into the transcript (a blank line followed by a
     * styled {@code " ❯ /cmd"} chip). Consolidates the {@code handleXxxDialogClose} chip echo.
     *
     * @param commandLabel the command as typed, e.g. {@code "/mcp"}
     */
    void breadcrumb(String commandLabel);

    /**
     * Appends a single colored line to the transcript. Consolidates
     * {@code LanternaReplScreen.appendLine}.
     */
    void line(String text, TextColor color);
}
