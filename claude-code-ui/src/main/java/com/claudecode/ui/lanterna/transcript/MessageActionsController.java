package com.claudecode.ui.lanterna.transcript;

import com.claudecode.ui.lanterna.input.InputPanel;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.Terminal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
/**
 * Owns keyboard message-selection actions independently of transcript mode.
 */
public final class MessageActionsController {

    private final Screen screen;
    private final MessagePanel messages;
    private final InputPanel input;
    private final Consumer<String> clipboard;
    private final Consumer<String> editMessage;
    private boolean active;

    public MessageActionsController(Terminal terminal, Screen screen,
                                    MessagePanel messages, InputPanel input) {
        this(terminal, screen, messages, input, _ -> {});
    }

    public MessageActionsController(Terminal terminal, Screen screen,
                                    MessagePanel messages, InputPanel input,
                                    Consumer<String> editMessage) {
        this(screen, messages, input, text -> emitClipboard(terminal, text), editMessage);
    }

    MessageActionsController(Screen screen, MessagePanel messages, InputPanel input,
                             Consumer<String> clipboard) {
        this(screen, messages, input, clipboard, _ -> {});
    }

    MessageActionsController(Screen screen, MessagePanel messages, InputPanel input,
                             Consumer<String> clipboard, Consumer<String> editMessage) {
        this.screen = screen;
        this.messages = messages;
        this.input = input;
        this.clipboard = clipboard != null ? clipboard : _ -> {};
        this.editMessage = editMessage != null ? editMessage : _ -> {};
    }

    public void toggle() {
        if (active) {
            exit();
            return;
        }
        Optional<MessagePanel.LogicalMessage> selected = messages.enterMessageActions();
        if (selected.isEmpty()) return;
        active = true;
        input.setMessageActionsActive(true);
        updateActionBar(selected.get());
        refreshComplete();
    }

    public void previous() { select(messages.selectPreviousLogicalMessage().orElse(null)); }
    public void next() { select(messages.selectNextLogicalMessage().orElse(null)); }
    public void previousUser() { select(messages.selectPreviousUserMessage().orElse(null)); }
    public void nextUser() { select(messages.selectNextUserMessage().orElse(null)); }
    public void top() { select(messages.selectTopLogicalMessage().orElse(null)); }
    public void bottom() { select(messages.selectBottomLogicalMessage().orElse(null)); }

    /** Esc first collapses an expanded target, then exits on the next press. */
    public void escape() {
        MessagePanel.LogicalMessage selected = messages.selectedLogicalMessage().orElse(null);
        if (active && selected != null && selected.expanded()) {
            messages.collapseSelectedLogicalMessage().ifPresent(this::updateActionBar);
            refresh();
            return;
        }
        exit();
    }

    /** Ctrl+C always leaves the overlay immediately. */
    public void forceExit() { exit(); }

    public void copy() {
        messages.selectedLogicalMessage()
            .map(MessagePanel.LogicalMessage::copyText)
            .filter(text -> !StringUtils.isBlank(text))
            .ifPresent(this::copyAndExit);
    }

    public void copyPrimaryInput() {
        messages.selectedLogicalMessage()
            .map(MessagePanel.LogicalMessage::primaryInput)
            .filter(text -> !StringUtils.isBlank(text))
            .ifPresent(this::copyAndExit);
    }

    /** Enter edits a user message; expandable message kinds toggle in-place. */
    public void edit() {
        MessagePanel.LogicalMessage selected = messages.selectedLogicalMessage().orElse(null);
        if (selected == null) return;
        if (selected.kind() == MessagePanel.LogicalMessageKind.USER
                && selected.sourceUuid() != null && !StringUtils.isBlank(selected.sourceUuid())) {
            String sourceUuid = selected.sourceUuid();
            exit();
            editMessage.accept(sourceUuid);
            return;
        }
        if (selected.expandable()) {
            messages.toggleSelectedLogicalMessageExpanded().ifPresent(this::updateActionBar);
            refresh();
        }
    }


    private void select(MessagePanel.LogicalMessage selected) {
        if (!active || selected == null) return;
        updateActionBar(selected);
        refresh();
    }

    private void copyAndExit(String text) {
        try {
            clipboard.accept(text);
        } catch (RuntimeException _) {
            // Clipboard delivery is best-effort.
        }
        exit();
        input.showTransientHint("Copied to clipboard", 1000);
    }

    private void exit() {
        active = false;
        messages.clearLogicalMessageSelection();
        input.setMessageActionsActive(false);
        refreshComplete();
    }

    private void updateActionBar(MessagePanel.LogicalMessage selected) {
        StringBuilder actions = new StringBuilder();
        if (selected.kind() == MessagePanel.LogicalMessageKind.USER
                && selected.sourceUuid() != null && !StringUtils.isBlank(selected.sourceUuid())) {
            actions.append("enter edit");
        } else if (selected.expandable()) {
            actions.append("enter ").append(selected.expanded() ? "collapse" : "expand");
        }
        if (StringUtils.isNotBlank(selected.copyText())) {
            appendAction(actions, "c copy");
        }
        if (StringUtils.isNotBlank(selected.primaryInput())) {
            String label = selected.primaryInputLabel() == null
                ? "input" : selected.primaryInputLabel();
            appendAction(actions, "p copy " + label);
        }
        input.setMessageActionsHint(actions.toString());
    }

    private static void appendAction(StringBuilder actions, String action) {
        if (!actions.isEmpty()) actions.append(" · ");
        actions.append(action);
    }

    private static void emitClipboard(Terminal terminal, String text) {
        if (terminal == null || text == null) return;
        try {
            terminal.emitOSC("52", "c;" + Base64.getEncoder()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8)));
            terminal.flush();
        } catch (Exception _) {
            // Clipboard delivery is best-effort.
        }
    }

    private void refresh() {
        if (screen == null) return;
        try {
            screen.refresh();
        } catch (Exception _) {
            // Refresh failure is non-fatal.
        }
    }

    private void refreshComplete() {
        if (screen == null) return;
        try {
            screen.refresh(Screen.RefreshType.COMPLETE);
        } catch (Exception _) {
            // Refresh failure is non-fatal.
        }
    }

    boolean isActiveForTest() { return active; }
}
