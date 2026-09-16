package com.claudecode.ui.lanterna.repl;

import com.claudecode.ui.lanterna.components.ChipSegments;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.googlecode.lanterna.TextColor;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link ReplTranscriptSink} over the live {@link MessagePanel}.
 *
 * <p>{@link #system} may be called from any thread and hops to the GUI thread; {@link #line}
 * and {@link #breadcrumb} are GUI-thread writes, matching how the extracted
 * {@code LanternaReplScreen.appendLine} / breadcrumb echo behaved.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/messages/SystemTextMessage.tsx} — dim inline system notification
 *       rows.</li>
 *   <li>{@code components/messages/UserPromptMessage.tsx} — the {@code " ❯ /cmd"} echo chip
 *       shown when a slash command dialog closes.</li>
 * </ul>
 */
final class MessagePanelTranscriptSink implements ReplTranscriptSink {

    private final MessagePanel messagePanel;
    private final Consumer<Runnable> guiInvoker;

    MessagePanelTranscriptSink(MessagePanel messagePanel, Consumer<Runnable> guiInvoker) {
        this.messagePanel = Objects.requireNonNull(messagePanel, "messagePanel");
        this.guiInvoker = Objects.requireNonNull(guiInvoker, "guiInvoker");
    }

    @Override
    public void system(String text) {
        if (StringUtils.isBlank(text)) return;
        guiInvoker.accept(() -> messagePanel.appendLine(text, LanternaTheme.welcomeDim()));
    }

    @Override
    public void line(String text, TextColor color) {
        messagePanel.appendLine(text, color);
    }

    @Override
    public void breadcrumb(String commandLabel) {
        messagePanel.appendLine("", TextColor.ANSI.DEFAULT);
        messagePanel.appendMixed(
            ChipSegments.of(" ❯ " + commandLabel,
                LanternaTheme.inputText(),
                LanternaTheme.claude(),
                LanternaTheme.userQueryBg()));
    }
}
