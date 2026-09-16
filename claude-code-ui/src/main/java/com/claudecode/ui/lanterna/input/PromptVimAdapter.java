package com.claudecode.ui.lanterna.input;

import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.vim.VimMode;
import com.claudecode.ui.vim.VimStateMachine;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.gui2.Interactable.Result;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.input.KeyStroke;

/**
 * Bridges the prompt text box to the {@link VimStateMachine} when vim
 * keybindings are on: translates keystrokes to vim input, mirrors the
 * machine's buffer/cursor back into the text box, keeps the
 * {@code -- INSERT --} label and terminal cursor shape in sync, and routes the
 * few keys vim must not own (arrows in INSERT, history, chips) back to the
 * editor.
 *
 * <p>The buffer is re-synced from the text box before every key because
 * readline shortcuts, paste chips and mouse clicks move the caret without
 * going through vim.
 *
 * <ul>
 *   <li>{@code src/hooks/useTextInput.ts} / {@code src/vim/*} — vim mode
 *       integration in the prompt: INSERT/NORMAL routing, Escape to NORMAL,
 *       Enter submitting from INSERT, arrows staying native in INSERT.</li>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — the
 *       {@code -- INSERT --} indicator and DECSCUSR cursor-shape sync.</li>
 * </ul>
 */
final class PromptVimAdapter {

    /** Editor access and the prompt actions vim delegates to. */
    interface Host {
        String text();
        int caret();
        /** Replaces the text without mode/query side effects. */
        void setTextRaw(String text);
        void moveCaretTo(int offset);
        /** Sends the key straight to the underlying Lanterna text box. */
        Result forwardToEditor(KeyStroke key);
        Result historyUp();
        Result historyDown();
        /** Chip-aware Backspace; false when no chip was at the caret. */
        boolean chipBackspace();
        boolean chipDelete();
        /** Mode re-detection + query-changed notification after vim mutated the text. */
        void textEdited();
        /** Enter in INSERT: clear the prompt and submit {@code text} (mode prefix applied by the panel). */
        void submit(String text);
        /** Escape in NORMAL: cancel the in-flight request. */
        void cancel();
        void cursorStyleChanged(CursorStyle style);
    }

    private final Host host;
    private final VimStateMachine vim = new VimStateMachine();
    private final Label label = new Label("");
    private boolean enabled;
    /**
     * True while a key is being forwarded to the underlying text box, so the
     * text box's own override sends it straight to Lanterna instead of
     * re-entering vim on the same key.
     */
    private boolean forwarding;

    PromptVimAdapter(Host host) {
        this.host = host;
        label.setForegroundColor(LanternaTheme.welcomeDim());
    }

    Label label() { return label; }

    boolean isEnabled() { return enabled; }

    boolean isForwarding() { return forwarding; }

    /** Current mode name for the status line, or null when vim is off. */
    String modeName() {
        return enabled ? vim.getMode().name() : null;
    }

    /** The text vim believes it is editing (only meaningful while enabled). */
    String buffer() { return vim.getBuffer(); }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            vim.reset();
            refreshLabel();
        } else {
            label.setText("");
        }
    }

    /** Pulls the text box into vim's buffer when they drifted apart. */
    void syncBuffer() {
        String boxText = host.text();
        if (!boxText.equals(vim.getBuffer())) vim.setBuffer(boxText);
    }

    /** Repaints the INSERT indicator and pushes the matching cursor shape. */
    void refreshLabel() {
        if (!enabled) {
            label.setText("");
            host.cursorStyleChanged(CursorStyle.DEFAULT);
            return;
        }
        VimMode mode = vim.getMode();
        label.setText(mode == VimMode.INSERT ? "  -- INSERT --" : "");
        host.cursorStyleChanged(switch (mode) {
            case INSERT -> CursorStyle.BLINKING_BAR;
            case NORMAL -> CursorStyle.STEADY_BLOCK;
        });
    }

    /** Handles one unmodified key while vim is enabled. */
    Result handleKey(KeyStroke key) {
        VimMode mode = vim.getMode();
        syncBuffer();
        setCursorSafely(host.caret());

        if (mode == VimMode.INSERT) {
            Result nativeResult = handleInsertNativeKey(key);
            if (nativeResult != null) return nativeResult;
        }

        char c = keystrokeToChar(key, mode);
        if (c == 0) {
            if (mode == VimMode.INSERT) {
                Result r = forward(key);
                syncBuffer();
                return r;
            }
            return Result.HANDLED;
        }
        if (c == '\n' || c == '\r') {
            // Enter in INSERT submits; in NORMAL it is a no-op.
            if (mode == VimMode.INSERT) {
                String text = vim.getBuffer().trim();
                vim.reset();
                host.setTextRaw("");
                refreshLabel();
                host.submit(text);
            }
            return Result.HANDLED;
        }
        if (c == 27) {
            if (mode == VimMode.INSERT) {
                vim.processKey(c);
                mirrorBufferToEditor();
                refreshLabel();
                return Result.HANDLED;
            }
            host.cancel();
            return Result.HANDLED;
        }

        vim.processKey(c);
        mirrorBufferToEditor();
        host.textEdited();
        refreshLabel();
        return Result.HANDLED;
    }

    /**
     * Keys that stay native in INSERT: arrows, history, and chip-aware
     * Backspace/Delete (vim would otherwise eat one character of an
     * {@code [Image #N]} token and leave garbage in the buffer).
     */
    private Result handleInsertNativeKey(KeyStroke key) {
        boolean plain = !key.isCtrlDown() && !key.isAltDown();
        return switch (key.getKeyType()) {
            case ARROW_LEFT, ARROW_RIGHT -> forward(key);
            // History routing must not go through forward(): the fence would
            // send the key straight to Lanterna and skip history navigation.
            case ARROW_UP -> plain && !key.isShiftDown() ? host.historyUp() : null;
            case ARROW_DOWN -> plain && !key.isShiftDown() ? host.historyDown() : null;
            case BACKSPACE -> plain ? chipEdit(key, host.chipBackspace()) : null;
            case DELETE -> plain ? chipEdit(key, host.chipDelete()) : null;
            default -> null;
        };
    }

    private Result chipEdit(KeyStroke key, boolean chipRemoved) {
        if (!chipRemoved) return forward(key);
        syncBuffer();
        setCursorSafely(host.caret());
        return Result.HANDLED;
    }

    private Result forward(KeyStroke key) {
        forwarding = true;
        try {
            return host.forwardToEditor(key);
        } finally {
            forwarding = false;
        }
    }

    /** Pushes vim's buffer and cursor into the text box so the caret stays aligned. */
    private void mirrorBufferToEditor() {
        host.setTextRaw(vim.getBuffer());
        host.moveCaretTo(vim.getCursor());
    }

    private void setCursorSafely(int caret) {
        try {
            vim.setCursor(caret);
        } catch (Exception _) {
            // A stale caret past the buffer end is harmless; the next sync fixes it.
        }
    }

    private static char keystrokeToChar(KeyStroke key, VimMode mode) {
        boolean insert = mode == VimMode.INSERT;
        return switch (key.getKeyType()) {
            case CHARACTER -> key.getCharacter();
            case BACKSPACE, DELETE -> (char) 127;
            case ESCAPE -> (char) 27;
            case ENTER -> '\n';
            case ARROW_LEFT -> insert ? (char) 0 : 'h';
            case ARROW_RIGHT -> insert ? (char) 0 : 'l';
            case ARROW_UP -> insert ? (char) 0 : 'k';
            case ARROW_DOWN -> insert ? (char) 0 : 'j';
            case HOME -> insert ? (char) 0 : '0';
            case END -> insert ? (char) 0 : '$';
            default -> (char) 0;
        };
    }
}
